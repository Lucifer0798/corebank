package com.corebank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountRepository;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.InterestService;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.fx.service.FxPositionService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.ReversalRequest;
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
 * Interactions between features, which no feature's own tests could catch.
 *
 * <p>Eleven PRs shipped reversals, scheduled transfers, holds, velocity limits, interest and FX in
 * quick succession, and each was tested thoroughly <em>in isolation</em>. Every test here sits in a
 * seam between two of them, and every one that fails before this PR is a bug that was green in both
 * of the PRs that created it.
 *
 * <p>Most of them trace to one change. #36 made the ledger balance per currency, which was right --
 * summed in total, a four-leg FX posting balances by coincidence. But it quietly turned every
 * internal account into an INR-only account, because cash and interest expense had only ever
 * existed in rupees. Anything pairing a dollar account with either of them could no longer balance.
 * The rule did its job: it failed loudly rather than posting 100 dollars against 100 rupees of cash,
 * which is what the pre-#36 ledger would have done in silence.
 */
@SpringBootTest
class CrossFeatureTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private InterestService interestService;

    @Autowired
    private FxPositionService fxPositionService;

    @Autowired
    private AccountRepository accounts;

    private UUID customerId;
    private UUID rupeeAccount;
    private int n;

    @BeforeEach
    void setUp() {
        n = UNIQUE.incrementAndGet();
        customerId = customerService.create(new CreateCustomerRequest(
                "Sana", "Malik", "sana.malik." + n + "@example.com", null,
                LocalDate.of(1989, 5, 20))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, "Test fixture", com.corebank.config.TestDeciders.STAFF);

        rupeeAccount = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(rupeeAccount,
                new AmountRequest(new BigDecimal("100000.00"), "INR", "Funding"), "xf-fund-" + n);
    }

    private UUID openAccount(String currency) {
        return accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, currency, BigDecimal.ZERO)).id();
    }

    // --- Non-INR accounts against INR-only internal accounts --------------------------------------

    @Test
    @DisplayName("cash can be deposited into a dollar account")
    void depositIntoAForeignCurrencyAccount() {
        // Before this PR: refused as UNBALANCED_POSTING. The deposit credits the customer in dollars
        // and debits cash -- which existed only in rupees -- so the dollar side never closed. Since
        // #36 a non-INR account could only be funded by an FX transfer, never by cash.
        UUID dollarAccount = openAccount("USD");

        TransactionResponse posted = transactionService.deposit(dollarAccount,
                new AmountRequest(new BigDecimal("100.00"), "USD", "Cash"), "xf-usd-dep-" + n);

        assertThat(accountService.get(dollarAccount).balance()).isEqualByComparingTo("100.00");
        // And the cash leg is in dollars, not rupees: the bank now holds more dollars.
        assertThat(posted.legs().get(0).accountNumber())
                .isNotEqualTo("GL0000000001")
                .describedAs("the rupee cash account must not be the contra leg of a dollar deposit");
    }

    @Test
    @DisplayName("cash can be withdrawn from a dollar account")
    void withdrawalFromAForeignCurrencyAccount() {
        UUID dollarAccount = openAccount("USD");
        transactionService.deposit(dollarAccount,
                new AmountRequest(new BigDecimal("100.00"), "USD", "Cash"), "xf-usd-dep2-" + n);

        transactionService.withdraw(dollarAccount,
                new AmountRequest(new BigDecimal("40.00"), "USD", "ATM"), "xf-usd-wd-" + n);

        assertThat(accountService.get(dollarAccount).balance()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("interest on a dollar savings account is actually paid")
    void interestCapitalisesOnAForeignCurrencyAccount() {
        // Before this PR: accrual worked, capitalisation never did. Capitalising credits the customer
        // in dollars and debits interest expense, which existed only in rupees. The runner caught the
        // failure and logged a warning every month, so a dollar account showed "interest accrued"
        // climbing for ever while not a cent of it was ever paid.
        UUID dollarAccount = openAccount("USD");
        transactionService.deposit(dollarAccount,
                new AmountRequest(new BigDecimal("10000.00"), "USD", "Cash"), "xf-usd-int-" + n);
        interestService.accrue(dollarAccount, LocalDate.of(2026, 6, 1));

        assertThat(interestService.capitalise(dollarAccount))
                .describedAs("a whole cent has accrued, so a posting must be produced")
                .isPresent();
        assertThat(accountService.get(dollarAccount).balance()).isGreaterThan(new BigDecimal("10000.00"));
    }

    @Test
    @DisplayName("an account cannot be opened in a currency the bank does not deal in")
    void anUnsupportedCurrencyIsRefusedAtOpening() {
        // Before this PR: opened without complaint, because only the ISO *shape* was validated. The
        // account was then dead for good -- no cash account to deposit against and no rate to convert
        // into it, so nothing could ever enter it. Better refused at the door than opened and useless.
        assertThatThrownBy(() -> accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "JPY", BigDecimal.ZERO)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("CURRENCY_NOT_SUPPORTED");
    }

    // --- Reversal against system-generated postings -----------------------------------------------

    @Test
    @DisplayName("an interest posting cannot be reversed")
    void interestCannotBeReversed() {
        // Before this PR: allowed, and it destroyed the customer's interest outright. Capitalising
        // moves the amount out of accrued_interest and into the balance; reversing took it back out
        // of the balance and restored nothing, so the interest vanished from both places at once.
        for (int day = 0; day < 31; day++) {
            interestService.accrue(rupeeAccount, LocalDate.of(2026, 6, 1).plusDays(day));
        }
        String reference = interestService.capitalise(rupeeAccount).orElseThrow();

        assertThatThrownBy(() -> transactionService.reverse(reference,
                new ReversalRequest("Trying to undo interest"), "xf-int-rev-" + n))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("NOT_REVERSIBLE");
    }

    @Test
    @DisplayName("an FX revaluation cannot be reversed")
    void revaluationCannotBeReversed() {
        // Before this PR: allowed, which broke the invariant #37 is built on -- that the revaluation
        // account's balance *is* the mark. The right correction for a wrong mark is to run the close
        // again, which posts the delta back to where the market says it should be.
        UUID dollarAccount = openAccount("USD");
        transactionService.transfer(new TransferRequest(rupeeAccount, dollarAccount,
                new BigDecimal("10000.00"), "INR", "Trade"), "xf-reval-trade-" + n);
        String reference = fxPositionService.revalue().orElseThrow();

        assertThatThrownBy(() -> transactionService.reverse(reference,
                new ReversalRequest("Trying to undo a close"), "xf-reval-rev-" + n))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("NOT_REVERSIBLE");
    }

    @Test
    @DisplayName("a cross-currency transfer reverses cleanly, both sides, at the rate it traded at")
    void anFxTransferReversesOnBothSides() {
        // An interaction that already worked, recorded so it stays that way. Reversing mirrors all
        // four legs, so each currency still balances on its own -- and it unwinds at the original
        // rate, not today's, because the mirrored amounts are the ones that actually moved.
        UUID dollarAccount = openAccount("USD");
        TransactionResponse trade = transactionService.transfer(new TransferRequest(rupeeAccount,
                dollarAccount, new BigDecimal("10000.00"), "INR", "Trade"), "xf-fxrev-" + n);
        assertThat(accountService.get(dollarAccount).balance()).isEqualByComparingTo("119.40");

        TransactionResponse reversal = transactionService.reverse(trade.reference(),
                new ReversalRequest("Wrong account"), "xf-fxrev-rev-" + n);

        assertThat(reversal.legs()).hasSize(4);
        assertThat(accountService.get(rupeeAccount).balance()).isEqualByComparingTo("100000.00");
        assertThat(accountService.get(dollarAccount).balance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an ordinary posting is still reversible")
    void ordinaryPostingsRemainReversible() {
        // The other half of making system postings non-reversible: the list of what *can* be undone
        // is now explicit, and the customer-initiated movements have to stay on it.
        TransactionResponse deposit = transactionService.deposit(rupeeAccount,
                new AmountRequest(new BigDecimal("500.00"), "INR", "Mistake"), "xf-ord-" + n);

        assertThatCode(() -> transactionService.reverse(deposit.reference(),
                new ReversalRequest("Keyed twice"), "xf-ord-rev-" + n))
                .doesNotThrowAnyException();
    }
}
