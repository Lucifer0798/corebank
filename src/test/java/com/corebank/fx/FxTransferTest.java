package com.corebank.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountRepository;
import com.corebank.account.service.AccountService;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.fx.service.FxRateService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cross-currency transfers, against a real ledger.
 *
 * <p>The assertions that matter here are not about the conversion arithmetic -- that is a
 * multiplication -- but about what the ledger claims afterwards. A four-leg posting can look
 * correct on the customer's side while leaving the bank's books saying something false, and the
 * position accounts are where that shows up.
 */
@SpringBootTest
class FxTransferTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private FxRateService fxRateService;

    @Autowired
    private AccountRepository accounts;

    private UUID rupeeAccount;
    private UUID dollarAccount;

    @BeforeEach
    void setUp() {
        int n = UNIQUE.incrementAndGet();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Farah", "Qureshi", "farah.qureshi." + n + "@example.com", null,
                LocalDate.of(1987, 7, 7))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, "Test fixture", com.corebank.config.TestDeciders.STAFF);

        rupeeAccount = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        dollarAccount = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "USD", BigDecimal.ZERO)).id();

        transactionService.deposit(rupeeAccount,
                new AmountRequest(new BigDecimal("100000.00"), "INR", "Funding"), "fx-fund-" + n);
    }

    private BigDecimal positionIn(String currency) {
        return accounts.findAll().stream()
                .filter(a -> a.getAccountType() == AccountType.FX_POSITION_GL)
                .filter(a -> a.getCurrency().equals(currency))
                .findFirst().orElseThrow().getBalance();
    }

    private TransactionResponse sendRupees(String amount, String key) {
        return transactionService.transfer(
                new TransferRequest(rupeeAccount, dollarAccount, new BigDecimal(amount), "INR", "To USD"),
                key);
    }

    @Test
    @DisplayName("a cross-currency transfer debits one currency and credits the other")
    void aCrossCurrencyTransferConverts() {
        // 10000 INR at 0.012 mid, less 50bps, is 0.01194 -> 119.40 USD.
        TransactionResponse posted = sendRupees("10000.00", "fx-1");

        assertThat(posted.fx()).isNotNull();
        assertThat(posted.fx().counterCurrency()).isEqualTo("USD");
        assertThat(posted.fx().counterAmount()).isEqualByComparingTo("119.40");
        assertThat(posted.fx().exchangeRate()).isEqualByComparingTo("0.01194000");

        assertThat(accountService.get(rupeeAccount).balance()).isEqualByComparingTo("90000.00");
        assertThat(accountService.get(dollarAccount).balance()).isEqualByComparingTo("119.40");
    }

    @Test
    @DisplayName("it posts four legs, two in each currency")
    void itPostsFourLegs() {
        // The shape is the point. Two legs cannot express this without the ledger asserting that
        // 10000 rupees *is* 119.40 dollars rather than that it was exchanged for them.
        TransactionResponse posted = sendRupees("10000.00", "fx-2");

        assertThat(posted.legs()).hasSize(4);
        assertThat(posted.legs().get(0).direction().name()).isEqualTo("DEBIT");
        assertThat(posted.legs().get(1).accountNumber()).isEqualTo("GL0000000010");
        assertThat(posted.legs().get(1).direction().name()).isEqualTo("CREDIT");
        assertThat(posted.legs().get(2).accountNumber()).isEqualTo("GL0000000011");
        assertThat(posted.legs().get(2).direction().name()).isEqualTo("DEBIT");
        assertThat(posted.legs().get(3).direction().name()).isEqualTo("CREDIT");
    }

    @Test
    @DisplayName("the bank's position moves by exactly what it bought and sold")
    void thePositionAccountsRecordTheTrade() {
        BigDecimal rupeesBefore = positionIn("INR");
        BigDecimal dollarsBefore = positionIn("USD");

        sendRupees("10000.00", "fx-3");

        // The bank took in 10000 rupees and paid out 119.40 dollars. Anything else here means the
        // books say the bank made a trade it did not make.
        assertThat(positionIn("INR").subtract(rupeesBefore)).isEqualByComparingTo("10000.00");
        assertThat(positionIn("USD").subtract(dollarsBefore))
                .describedAs("paying a currency out drives the position negative -- the bank is short")
                .isEqualByComparingTo("-119.40");
    }

    @Test
    @DisplayName("the spread is the bank's, in whichever direction the trade runs")
    void theSpreadAlwaysFavoursTheBank() {
        // Converting one way and straight back must lose the customer money twice over. If the
        // spread were applied with the wrong sign in either direction, a round trip would be free
        // or profitable, and a customer could mint money by bouncing between two accounts.
        var out = fxRateService.quote("INR", "USD", new BigDecimal("10000.00"));
        var back = fxRateService.quote("USD", "INR", out.toAmount());

        assertThat(out.rateApplied()).isLessThan(out.midRate());
        assertThat(back.rateApplied()).isLessThan(back.midRate());
        assertThat(back.toAmount())
                .describedAs("a round trip returns less than it started with")
                .isLessThan(new BigDecimal("10000.00"));
    }

    @Test
    @DisplayName("a pair the bank does not quote is refused clearly")
    void anUnquotedPairIsRefused() {
        assertThatThrownBy(() -> fxRateService.quote("INR", "JPY", new BigDecimal("100.00")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("FX_RATE_UNAVAILABLE");
    }

    @Test
    @DisplayName("an amount that converts to nothing is refused rather than posted as zero")
    void aVanishinglySmallConversionIsRefused() {
        // 0.01 INR is well under a cent. Left alone this would build a zero-amount leg and fail on
        // the ledger's own amount constraint, which is a worse error message than the truth.
        assertThatThrownBy(() -> sendRupees("0.01", "fx-tiny"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("FX_AMOUNT_TOO_SMALL");
    }

    @Test
    @DisplayName("a same-currency transfer is untouched by any of this")
    void sameCurrencyIsStillTwoLegs() {
        UUID otherRupeeAccount = accountService.open(new OpenAccountRequest(
                accountService.get(rupeeAccount).customerId(), AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();

        TransactionResponse posted = transactionService.transfer(
                new TransferRequest(rupeeAccount, otherRupeeAccount, new BigDecimal("500.00"), "INR", "Domestic"),
                "fx-same");

        assertThat(posted.legs()).hasSize(2);
        assertThat(posted.fx())
                .describedAs("nothing was converted, so there is no rate to record")
                .isNull();
    }

    @Test
    @DisplayName("the quote shows the margin rather than hiding it in the rate")
    void theQuoteSeparatesMidFromSpread() {
        // A rate already net of spread cannot be checked against any published source, so the two
        // halves of what the customer is charged stay visible.
        var quote = fxRateService.quote("INR", "USD", new BigDecimal("10000.00"));

        assertThat(quote.midRate()).isEqualByComparingTo("0.01200000");
        assertThat(quote.spreadBps()).isEqualTo(50);
        assertThat(quote.rateApplied()).isEqualByComparingTo("0.01194000");
        assertThat(quote.toAmount()).isEqualByComparingTo("119.40");
    }
}
