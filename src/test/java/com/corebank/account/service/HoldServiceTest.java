package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.HoldStatus;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.HoldResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.repository.AccountHoldRepository;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ConflictException;
import com.corebank.common.exception.InsufficientFundsException;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import com.corebank.transaction.service.VelocityLimits;
import com.corebank.transaction.service.ReferenceGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds against a real database and real postings -- in particular the capture path, which is the
 * only place a hold turns into money actually moving.
 */
@SpringBootTest
class HoldServiceTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private HoldService holdService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountHoldRepository holds;

    @Autowired
    private ReferenceGenerator referenceGenerator;

    @Autowired
    private VelocityLimits velocityLimits;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        int n = UNIQUE.incrementAndGet();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Nadia", "Rahman", "nadia.rahman." + n + "@example.com", null,
                LocalDate.of(1988, 6, 2))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, "Test fixture", com.corebank.config.TestDeciders.STAFF);

        accountId = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("1000.00"), "INR", "Funding"), "hold-fund-" + n);
    }

    private HoldResponse place(String amount) {
        return holdService.place(accountId,
                new PlaceHoldRequest(new BigDecimal(amount), "INR", "Hotel authorisation", null));
    }

    private BigDecimal available() {
        return accountService.get(accountId).availableBalance();
    }

    private BigDecimal balance() {
        return accountService.get(accountId).balance();
    }

    /**
     * A second service that believes it is {@code days} from now.
     *
     * <p>Backdating a stored hold's {@code expires_at} was the obvious way to test expiry and is
     * the wrong one: {@code ck_hold_window} refuses an expiry before the placement, correctly, so
     * the test could only pass by weakening a constraint worth keeping. Moving the clock instead
     * leaves every invariant intact and is the same trick {@code ScheduledTransferRunnerTest}
     * uses -- the default hold lasts seven days, so eight puts any of them in the past.
     */
    /**
     * Runs work against a hand-built service inside a transaction. The one built below is a
     * plain object rather than a Spring bean, so its @Transactional annotations mean nothing
     * and its pessimistic locks have no transaction to belong to.
     */
    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private HoldService serviceDaysFromNow(int days) {
        return new HoldService(holds, accountService, transactionService, referenceGenerator,
                velocityLimits, meterRegistry,
                Clock.fixed(Instant.now().plus(Duration.ofDays(days)), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("placing a hold reserves money without posting anything")
    void placingReserves() {
        HoldResponse hold = place("400.00");

        assertThat(hold.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.reference()).startsWith("HLD-");
        assertThat(balance()).isEqualByComparingTo("1000.00");
        assertThat(available()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("capturing the full amount posts it and clears the reservation")
    void capturingInFull() {
        HoldResponse hold = place("400.00");

        HoldResponse captured = holdService.capture(hold.reference(), new CaptureHoldRequest(null));

        assertThat(captured.status()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(captured.capturedTransactionReference()).startsWith("TXN-");
        assertThat(balance()).isEqualByComparingTo("600.00");
        assertThat(available())
                .describedAs("the reservation is gone as well as the money")
                .isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("capturing less than was held returns the difference")
    void capturingLess() {
        // The ordinary case: a pre-authorisation is an upper bound and the final bill is lower.
        HoldResponse hold = place("400.00");

        holdService.capture(hold.reference(), new CaptureHoldRequest(new BigDecimal("250.00")));

        assertThat(balance()).isEqualByComparingTo("750.00");
        assertThat(available()).isEqualByComparingTo("750.00");
    }

    @Test
    @DisplayName("a capture for the held amount succeeds even when nothing else is left")
    void aFullCaptureIsGuaranteed() {
        // The guarantee holds the whole feature together: money reserved for this capture is the
        // money it spends, so nothing that happened in between can make it fail.
        HoldResponse hold = place("400.00");
        transactionService.withdraw(accountId,
                new AmountRequest(new BigDecimal("600.00"), "INR", "Spending the rest"), "spend-rest");
        assertThat(available()).isEqualByComparingTo("0.00");

        holdService.capture(hold.reference(), new CaptureHoldRequest(null));

        assertThat(balance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("capturing more than was held is allowed when the excess is affordable")
    void capturingMoreThanHeld() {
        // A tip added after the pre-authorisation. Only the held portion was guaranteed; the
        // excess competes with the available balance like any other withdrawal.
        HoldResponse hold = place("400.00");

        holdService.capture(hold.reference(), new CaptureHoldRequest(new BigDecimal("450.00")));

        assertThat(balance()).isEqualByComparingTo("550.00");
    }

    @Test
    @DisplayName("an unaffordable excess is refused and leaves the hold intact")
    void anUnaffordableExcessLeavesTheHold() {
        HoldResponse hold = place("400.00");
        transactionService.withdraw(accountId,
                new AmountRequest(new BigDecimal("600.00"), "INR", "Spending the rest"), "spend-rest-2");

        assertThatThrownBy(() -> holdService.capture(hold.reference(),
                new CaptureHoldRequest(new BigDecimal("450.00"))))
                .isInstanceOf(InsufficientFundsException.class);

        // The rollback has to put the reservation back, or a refused capture would quietly
        // release the hold and let somebody else spend the guaranteed money.
        HoldResponse after = holdService.get(hold.reference());
        assertThat(after.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(balance()).isEqualByComparingTo("400.00");
        assertThat(available()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("releasing gives the money back and posts nothing")
    void releasing() {
        HoldResponse hold = place("400.00");

        HoldResponse released = holdService.release(hold.reference());

        assertThat(released.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(released.capturedTransactionReference())
                .describedAs("nothing moved, so there is no posting to name")
                .isNull();
        assertThat(balance()).isEqualByComparingTo("1000.00");
        assertThat(available()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a settled hold cannot be captured or released again")
    void settlingIsTerminal() {
        HoldResponse hold = place("400.00");
        holdService.release(hold.reference());

        assertThatThrownBy(() -> holdService.capture(hold.reference(), new CaptureHoldRequest(null)))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("HOLD_NOT_ACTIVE");
        assertThatThrownBy(() -> holdService.release(hold.reference()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an expired hold cannot be captured, even before the sweep has seen it")
    void anExpiredHoldIsNotCapturable() {
        // The sweep runs on an interval, so there is always a window where a hold is over but
        // still ACTIVE in the database. A capture arriving then must be refused on the clock,
        // not honoured because a background job happened not to have run.
        HoldResponse hold = place("400.00");
        HoldService later = serviceDaysFromNow(8);

        assertThatThrownBy(() -> inTransaction(
                () -> later.capture(hold.reference(), new CaptureHoldRequest(null))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("HOLD_EXPIRED");
    }

    @Test
    @DisplayName("the sweep frees an expired hold")
    void sweepingExpiresAndFrees() {
        HoldResponse hold = place("400.00");
        HoldService later = serviceDaysFromNow(8);

        inTransaction(() -> later.findExpired().forEach(later::expire));

        assertThat(holdService.get(hold.reference()).status()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(available()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("the denormalised held total always matches the holds behind it")
    void theHeldTotalReconciles() {
        // held_amount is maintained incrementally, so nothing in the running application would
        // notice it drifting. This is the assertion that says it did not.
        place("100.00");
        HoldResponse second = place("250.00");
        place("50.00");
        holdService.release(second.reference());

        BigDecimal fromHolds = holds.sumOutstandingFor(accountId);

        assertThat(accountService.require(accountId).getHeldAmount()).isEqualByComparingTo(fromHolds);
        assertThat(fromHolds).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("a frozen account takes no new holds")
    void aFrozenAccountRefusesAHold() {
        accountService.changeStatus(accountId, AccountStatus.FROZEN);

        assertThatThrownBy(() -> place("100.00"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("ACCOUNT_FROZEN");
        assertThat(accountService.require(accountId).getHeldAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a hold placed before a freeze cannot be captured while it lasts")
    void aFreezeAlsoStopsCapturingEarlierHolds() {
        // Pinned on purpose, because it is a policy and not an accident: a capture is a withdrawal,
        // and a freeze -- often fraud or a legal order -- stops withdrawals. Card schemes usually
        // honour an authorisation already given, so if that is ever wanted, this is the test that
        // should change, deliberately.
        String reference = place("100.00").reference();
        accountService.changeStatus(accountId, AccountStatus.FROZEN);

        assertThatThrownBy(() -> holdService.capture(reference, new CaptureHoldRequest(null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("ACCOUNT_FROZEN");
        assertThat(holdService.get(reference).status())
                .describedAs("the refusal leaves the hold outstanding, to capture after an unfreeze or release")
                .isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    @DisplayName("a hold beyond the available balance is refused")
    void anUnaffordableHoldIsRefused() {
        assertThatThrownBy(() -> place("5000.00")).isInstanceOf(InsufficientFundsException.class);
        assertThat(accountService.require(accountId).getHeldAmount()).isEqualByComparingTo("0.00");
    }
}
