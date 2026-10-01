package com.corebank.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountRepository;
import com.corebank.account.service.AccountService;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.fx.dto.FxPositionReport;
import com.corebank.fx.service.FxPositionService;
import com.corebank.fx.service.FxRateService;
import com.corebank.transaction.dto.AmountRequest;
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
 * Valuing the FX book, and the posting that recognises a change in it.
 *
 * <p>These assertions are about the bank's own books rather than any customer's. The trading in
 * {@code FxTransferTest} already checks that a customer gets the right money; what is checkable
 * only here is whether the bank can then say what it is holding, and whether the number it reports
 * survives being marked twice.
 */
@SpringBootTest
class FxPositionTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private FxPositionService fxPositionService;

    @Autowired
    private FxRateService fxRateService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AccountRepository accounts;

    private UUID rupeeAccount;
    private UUID dollarAccount;

    @BeforeEach
    void setUp() {
        int n = UNIQUE.incrementAndGet();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Omar", "Siddiqui", "omar.siddiqui." + n + "@example.com", null,
                LocalDate.of(1983, 11, 3))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED);

        rupeeAccount = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        dollarAccount = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "USD", BigDecimal.ZERO)).id();
        transactionService.deposit(rupeeAccount,
                new AmountRequest(new BigDecimal("500000.00"), "INR", "Funding"), "pos-fund-" + n);
    }

    private void tradeRupeesForDollars(String amount, String key) {
        transactionService.transfer(
                new TransferRequest(rupeeAccount, dollarAccount, new BigDecimal(amount), "INR", "Trade"),
                key);
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("the report lists every position account, valued into the reporting currency")
    void theReportCoversTheWholeBook() {
        FxPositionReport report = fxPositionService.report();

        assertThat(report.reportingCurrency()).isEqualTo("INR");
        assertThat(report.positions())
                .describedAs("one line per currency the bank deals in, whether or not it has traded")
                .extracting(FxPositionReport.Line::currency)
                .containsExactly("EUR", "GBP", "INR", "USD");

        // The reporting currency needs no conversion, and asking the rate table for INR/INR would
        // be a lookup with no row and no meaning.
        assertThat(report.positions().stream()
                .filter(line -> line.currency().equals("INR"))
                .findFirst().orElseThrow().rateToReporting())
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a trade shows up as offsetting positions whose net is the bank's gain")
    void aTradeLeavesAValuedPosition() {
        BigDecimal markBefore = fxPositionService.report().markToMarket();

        tradeRupeesForDollars("10000.00", "pos-trade-1");

        // Took in 10000 INR, paid out 119.40 USD. At the quoted USD/INR mid of 83.00 that is
        // 9910.20 INR given up, so the book is 89.80 INR better off.
        FxPositionReport report = fxPositionService.report();
        assertThat(report.markToMarket().subtract(markBefore)).isEqualByComparingTo("89.80");

        FxPositionReport.Line usd = report.positions().stream()
                .filter(line -> line.currency().equals("USD")).findFirst().orElseThrow();
        assertThat(usd.rateToReporting())
                .describedAs("the directly quoted USD/INR, not the inverse of INR/USD -- they differ by 0.4%")
                .isEqualByComparingTo("83.00000000");
    }

    @Test
    @DisplayName("positions are valued at mid, not at the bank's own spread")
    void positionsAreValuedAtMid() {
        // Marking a book at the spread you would charge to close it reports a profit you have not
        // made. The rate on the report has to be the mid rate, not the effective one.
        tradeRupeesForDollars("10000.00", "pos-mid");

        FxPositionReport.Line usd = fxPositionService.report().positions().stream()
                .filter(line -> line.currency().equals("USD")).findFirst().orElseThrow();

        assertThat(usd.rateToReporting())
                .isEqualByComparingTo(fxRateService.require("USD", "INR").getMidRate())
                .isNotEqualByComparingTo(fxRateService.require("USD", "INR").effectiveRate());
    }

    @Test
    @DisplayName("a close recognises the gap, and the reserve then carries the mark")
    void aCloseRecognisesTheGap() {
        tradeRupeesForDollars("10000.00", "pos-close-1");
        FxPositionReport before = fxPositionService.report();
        assertThat(before.unrecognised()).isEqualByComparingTo(before.markToMarket().subtract(before.markCarried()));

        String reference = fxPositionService.revalue().orElseThrow();

        // The invariant worth having: afterwards the reserve account *is* the mark. That makes
        // "was the revaluation applied correctly" a question with a checkable answer.
        assertThat(balanceOf("GL0000000014")).isEqualByComparingTo(before.markToMarket());
        assertThat(fxPositionService.report().unrecognised()).isEqualByComparingTo("0.00");
        assertThat(reference).startsWith("FXR-");
    }

    @Test
    @DisplayName("closing twice in a row posts nothing the second time")
    void aSecondCloseIsANoOp() {
        // The reason a close posts the delta rather than the mark. Posting the mark would double
        // the book's value on every run, and a nightly job that ran twice would be unrecoverable.
        tradeRupeesForDollars("10000.00", "pos-twice");
        fxPositionService.revalue();
        BigDecimal afterFirst = balanceOf("GL0000000014");

        assertThat(fxPositionService.revalue())
                .describedAs("nothing has moved, so there is nothing to recognise")
                .isEmpty();
        assertThat(balanceOf("GL0000000014")).isEqualByComparingTo(afterFirst);
    }

    @Test
    @DisplayName("a close after more trading recognises only what is new")
    void aLaterCloseIsIncremental() {
        tradeRupeesForDollars("10000.00", "pos-inc-1");
        fxPositionService.revalue();
        BigDecimal markAfterFirst = balanceOf("GL0000000014");

        tradeRupeesForDollars("10000.00", "pos-inc-2");
        fxPositionService.revalue();

        // A second identical trade adds the same 89.80 again, and only that.
        assertThat(balanceOf("GL0000000014").subtract(markAfterFirst)).isEqualByComparingTo("89.80");
    }

    @Test
    @DisplayName("the gain is recognised against the P&L account, and the posting balances")
    void theGainLandsInProfitAndLoss() {
        BigDecimal gainBefore = balanceOf("GL0000000015");
        tradeRupeesForDollars("10000.00", "pos-pl");

        String reference = fxPositionService.revalue().orElseThrow();

        var posting = transactionService.getByReference(reference);
        assertThat(posting.type().name()).isEqualTo("FX_REVALUATION");
        assertThat(posting.legs()).hasSize(2);
        assertThat(posting.currency()).isEqualTo("INR");
        // Both legs in the reporting currency, necessarily: the per-currency balance rule means a
        // posting mixing INR with USD could not balance at all.
        assertThat(balanceOf("GL0000000015").subtract(gainBefore)).isEqualByComparingTo("89.80");
    }

    @Test
    @DisplayName("the foreign positions are untouched by a close")
    void revaluationDoesNotTouchThePositions() {
        // What makes the gain *unrealised*. Nothing has happened to the dollars; the bank has only
        // restated what holding them is worth.
        tradeRupeesForDollars("10000.00", "pos-untouched");
        BigDecimal usdBefore = balanceOf("GL0000000011");
        BigDecimal inrBefore = balanceOf("GL0000000010");

        fxPositionService.revalue();

        assertThat(balanceOf("GL0000000011")).isEqualByComparingTo(usdBefore);
        assertThat(balanceOf("GL0000000010")).isEqualByComparingTo(inrBefore);
    }

    @Test
    @DisplayName("no sequence of conversions returns more than it started with")
    void thereIsNoArbitrageInTheQuotedBook() {
        // The seeded cross-rates are not mutually consistent -- USD/INR is 83.00 while 1/(INR/USD)
        // is 83.33, and the other pairs disagree by 0.1% to 0.64%. That is tolerable only while
        // the spread covers it. If a rate is ever edited so it does not, a customer could mint
        // money by cycling between their own accounts, and this is the assertion that notices.
        assertThat(roundTrip("INR", "USD")).isLessThan(BigDecimal.ONE);
        assertThat(roundTrip("INR", "EUR")).isLessThan(BigDecimal.ONE);
        assertThat(roundTrip("INR", "GBP")).isLessThan(BigDecimal.ONE);
        assertThat(roundTrip("USD", "EUR")).isLessThan(BigDecimal.ONE);

        // And the triangle, which a pair of round trips would not catch.
        assertThat(triangle("INR", "USD", "EUR")).isLessThan(BigDecimal.ONE);
        assertThat(triangle("INR", "EUR", "USD")).isLessThan(BigDecimal.ONE);
    }

    /** One unit of {@code a} taken to {@code b} and back, at the rates a customer would get. */
    private BigDecimal roundTrip(String a, String b) {
        BigDecimal out = fxRateService.require(a, b).effectiveRate();
        return out.multiply(fxRateService.require(b, a).effectiveRate());
    }

    private BigDecimal triangle(String a, String b, String c) {
        return fxRateService.require(a, b).effectiveRate()
                .multiply(fxRateService.require(b, c).effectiveRate())
                .multiply(fxRateService.require(c, a).effectiveRate());
    }
}
