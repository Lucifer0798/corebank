package com.corebank.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.HoldService;
import com.corebank.common.exception.LimitExceededException;
import com.corebank.config.TestProperties;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.ReversalRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Velocity limits against a real ledger.
 *
 * <p>The limit is read from the entries rather than a counter, so the cases worth testing hardest
 * are the ones a counter would have had to remember to handle: a reversed withdrawal giving the
 * allowance back, a deposit <em>not</em> topping it up, and a hold capture settling without being
 * refused by a ceiling the authorisation already cleared.
 *
 * <p>A hand-built {@link VelocityLimits} with small numbers is used rather than the suite's
 * configured ones, which are deliberately enormous -- reaching a realistic limit through H2 would
 * mean posting hundreds of thousands of rupees to assert one boundary.
 */
@SpringBootTest
class VelocityLimitsTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private HoldService holdService;

    @Autowired
    private LedgerEntryRepository entries;

    @Autowired
    private com.corebank.fx.service.FxRateService fxRateService;

    private UUID accountId;
    private UUID otherId;

    @BeforeEach
    void setUp() {
        int n = UNIQUE.incrementAndGet();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Imran", "Sheikh", "imran.sheikh." + n + "@example.com", null,
                LocalDate.of(1985, 4, 11))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED);

        accountId = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        otherId = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("10000.00"), "INR", "Funding"), "vl-fund-" + n);
    }

    /** Limits with the numbers a test can actually reach. */
    private VelocityLimits limits(String daily, String single) {
        return new VelocityLimits(entries, TestProperties.withLimits(daily, single), fxRateService, Clock.systemUTC());
    }

    private void withdraw(String amount, String key) {
        transactionService.withdraw(accountId, new AmountRequest(new BigDecimal(amount), "INR", "ATM"), key);
    }

    @Test
    @DisplayName("the day's total counts withdrawals and outgoing transfers, and nothing else")
    void whatCountsTowardsTheDailyTotal() {
        VelocityLimits limits = limits("1000.00", "1000.00");
        assertThat(limits.debitedToday(accountId)).isEqualByComparingTo("0.00");

        withdraw("200.00", "vl-w1-" + accountId);
        assertThat(limits.debitedToday(accountId)).isEqualByComparingTo("200.00");

        transactionService.transfer(
                new TransferRequest(accountId, otherId, new BigDecimal("300.00"), "INR", "Out"),
                "vl-t1-" + accountId);
        assertThat(limits.debitedToday(accountId)).isEqualByComparingTo("500.00");

        // A deposit is not a debit, and must not buy back allowance -- otherwise the limit could
        // be lifted indefinitely by cycling money in and out.
        transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("5000.00"), "INR", "More"), "vl-d1-" + accountId);
        assertThat(limits.debitedToday(accountId)).isEqualByComparingTo("500.00");

        // Money arriving from the other side is not this account's debit either.
        transactionService.transfer(
                new TransferRequest(otherId, accountId, new BigDecimal("100.00"), "INR", "In"),
                "vl-t2-" + accountId);
        assertThat(limits.debitedToday(accountId)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("reversing a withdrawal gives the allowance back")
    void reversalRestoresAllowance() {
        // The case a counter column would have had to remember: the bank undid its own posting, so
        // charging the customer's daily allowance for it would be charging them for a bank error.
        VelocityLimits limits = limits("1000.00", "1000.00");
        TransactionResponse posted = transactionService.withdraw(accountId,
                new AmountRequest(new BigDecimal("800.00"), "INR", "Keyed twice"), "vl-rev-" + accountId);
        assertThat(limits.debitedToday(accountId)).isEqualByComparingTo("800.00");

        transactionService.reverse(posted.reference(),
                new ReversalRequest("Duplicate withdrawal"), "vl-rev-key-" + accountId);

        assertThat(limits.debitedToday(accountId))
                .describedAs("the original no longer counts, and the correcting credit never did")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("reversing a deposit does not consume the allowance either")
    void reversedDepositDoesNotConsumeAllowance() {
        // Reversing a deposit debits the customer. That debit is the bank unwinding its own error,
        // not the customer spending, so it must not eat into what they may withdraw today.
        VelocityLimits limits = limits("1000.00", "1000.00");
        TransactionResponse deposit = transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("900.00"), "INR", "Wrong account"), "vl-dep-" + accountId);

        transactionService.reverse(deposit.reference(),
                new ReversalRequest("Posted to the wrong account"), "vl-dep-rev-" + accountId);

        assertThat(limits.debitedToday(accountId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a single posting over the per-transaction ceiling is refused")
    void singlePostingCeiling() {
        VelocityLimits limits = limits("100000.00", "500.00");

        assertThatThrownBy(() -> limits.assertWithin(accountId, "INR", new BigDecimal("500.01")))
                .isInstanceOf(LimitExceededException.class)
                .extracting(ex -> ((LimitExceededException) ex).getCode())
                .isEqualTo("TRANSACTION_LIMIT_EXCEEDED");

        assertThatCode(() -> limits.assertWithin(accountId, "INR", new BigDecimal("500.00")))
                .describedAs("exactly the ceiling is allowed")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the daily total is a ceiling on the sum, not on each posting")
    void dailyCeiling() {
        VelocityLimits limits = limits("1000.00", "1000.00");
        withdraw("600.00", "vl-day1-" + accountId);

        assertThatCode(() -> limits.assertWithin(accountId, "INR", new BigDecimal("400.00")))
                .describedAs("exactly exhausting the allowance is allowed")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> limits.assertWithin(accountId, "INR", new BigDecimal("400.01")))
                .isInstanceOf(LimitExceededException.class)
                .extracting(ex -> ((LimitExceededException) ex).getCode())
                .isEqualTo("DAILY_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("the refusal says how much allowance is left")
    void theRefusalIsActionable() {
        // "Limit exceeded" alone sends a caller to guess. The remaining figure is the one thing
        // they can act on, so it is in the message.
        VelocityLimits limits = limits("1000.00", "1000.00");
        withdraw("750.00", "vl-msg-" + accountId);

        assertThatThrownBy(() -> limits.assertWithin(accountId, "INR", new BigDecimal("300.00")))
                .hasMessageContaining("250.00");
    }

    @Test
    @DisplayName("outstanding holds count at authorisation, so holds are not a way round the limit")
    void holdsCountAtAuthorisation() {
        VelocityLimits limits = limits("1000.00", "1000.00");

        limits.assertWithin(accountId, "INR", new BigDecimal("400.00"), new BigDecimal("400.00"));

        assertThatThrownBy(() -> limits.assertWithin(accountId, "INR",
                new BigDecimal("400.00"), new BigDecimal("700.00")))
                .isInstanceOf(LimitExceededException.class)
                .extracting(ex -> ((LimitExceededException) ex).getCode())
                .isEqualTo("DAILY_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("capturing a hold is never refused by the limit")
    void capturingIsExemptFromTheLimit() {
        // Only that the path works end to end. This cannot prove the exemption, because the suite's
        // configured limits are large enough that a small capture passes whether or not the check
        // runs -- removing the exemption leaves this test green. HoldCaptureLimitExemptionTest
        // proves it properly, with its own small limits.
        var hold = holdService.place(accountId,
                new PlaceHoldRequest(new BigDecimal("500.00"), "INR", "Hotel", null));

        assertThatCode(() -> holdService.capture(hold.reference(), new CaptureHoldRequest(null)))
                .doesNotThrowAnyException();

        assertThat(accountService.get(accountId).balance()).isEqualByComparingTo("9500.00");
    }
}
