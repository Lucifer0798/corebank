package com.corebank.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.schedule.ScheduledTransferRunner;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.domain.ScheduleStatus;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.dto.ScheduledTransferResponse;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The runner against a real database, real postings and the real idempotency machinery -- the
 * parts {@code ScheduledTransferTest} deliberately leaves out.
 *
 * <p>The runner is built here by hand rather than autowired, for one reason: its {@link Clock}.
 * The bean is disabled in this suite (a background poller that moves money has no business
 * running under unrelated tests), and constructing it lets each test hand it a different day, so
 * "a week of a daily standing order" is something the test states rather than waits for.
 *
 * <p>Mandates are still created through the service, which validates against the real clock, so
 * every schedule here starts today. That is the honest arrangement: creation is a request, and
 * requests happen now; only the running of it is time-travelled.
 */
@SpringBootTest
class ScheduledTransferRunnerTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private ScheduledTransferService scheduledTransfers;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    private UUID source;
    private UUID destination;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(Clock.systemUTC());

        int n = UNIQUE.incrementAndGet();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Ravi", "Iyer", "ravi.iyer." + n + "@example.com", null,
                LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED);

        source = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        destination = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();

        transactionService.deposit(source, new AmountRequest(new BigDecimal("1000.00"), "INR", "Funding"),
                "fund-" + n);
    }

    /** A runner that believes today is {@code date}. */
    private ScheduledTransferRunner runnerOn(LocalDate date) {
        return new ScheduledTransferRunner(
                scheduledTransfers,
                new SimpleMeterRegistry(),
                Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    private ScheduledTransferResponse schedule(ScheduleFrequency frequency, String amount, LocalDate endsOn) {
        return scheduledTransfers.create(new CreateScheduledTransferRequest(
                source, destination, new BigDecimal(amount), "INR", "Rent", frequency, today, endsOn));
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountService.get(accountId).balance();
    }

    @Test
    @DisplayName("a due mandate posts, and the money is actually in the other account")
    void aDueMandatePosts() {
        ScheduledTransferResponse created = schedule(ScheduleFrequency.DAILY, "250.00", null);
        assertThat(created.nextRunOn()).isEqualTo(today);

        runnerOn(today).run();

        assertThat(balanceOf(source)).isEqualByComparingTo("750.00");
        assertThat(balanceOf(destination)).isEqualByComparingTo("250.00");

        ScheduledTransferResponse after = scheduledTransfers.get(created.id());
        assertThat(after.runsCompleted()).isEqualTo(1);
        assertThat(after.nextRunOn()).isEqualTo(today.plusDays(1));
        assertThat(after.status()).isEqualTo(ScheduleStatus.ACTIVE);
    }

    @Test
    @DisplayName("running twice on the same day posts once")
    void runningTwiceInADayPostsOnce() {
        // Not idempotency doing the work here -- the mandate is simply no longer due, which is
        // what stops a 60-second poll from paying the rent sixty times an hour.
        ScheduledTransferResponse created = schedule(ScheduleFrequency.DAILY, "250.00", null);

        runnerOn(today).run();
        runnerOn(today).run();

        assertThat(balanceOf(destination)).isEqualByComparingTo("250.00");
        assertThat(scheduledTransfers.get(created.id()).runsCompleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("a mandate several days behind catches up one occurrence per tick")
    void catchesUpAfterAnOutage() {
        // The application being down for three days must not lose three payments, and must not
        // post them in one indivisible burst either.
        ScheduledTransferResponse created = schedule(ScheduleFrequency.DAILY, "100.00", null);
        ScheduledTransferRunner threeDaysLater = runnerOn(today.plusDays(3));

        threeDaysLater.run();
        assertThat(scheduledTransfers.get(created.id()).runsCompleted()).isEqualTo(1);

        threeDaysLater.run();
        threeDaysLater.run();
        threeDaysLater.run();

        ScheduledTransferResponse after = scheduledTransfers.get(created.id());
        assertThat(after.runsCompleted())
                .describedAs("four occurrences were due: today plus the three missed days")
                .isEqualTo(4);
        assertThat(balanceOf(destination)).isEqualByComparingTo("400.00");
        assertThat(after.nextRunOn()).isEqualTo(today.plusDays(4));
    }

    @Test
    @DisplayName("an unaffordable occurrence is skipped, and the mandate carries on")
    void anUnaffordableOccurrenceIsSkipped() {
        ScheduledTransferResponse created = schedule(ScheduleFrequency.DAILY, "5000.00", null);

        runnerOn(today).run();

        ScheduledTransferResponse after = scheduledTransfers.get(created.id());
        assertThat(after.status()).isEqualTo(ScheduleStatus.ACTIVE);
        assertThat(after.runsCompleted()).isZero();
        assertThat(after.consecutiveFailures()).isEqualTo(1);
        assertThat(after.nextRunOn()).isEqualTo(today.plusDays(1));
        // Recorded so an operator reading the mandate can see why without going to the logs;
        // the requested amount is the part that tells them whether to expect it to clear later.
        assertThat(after.lastError()).isNotBlank().contains("5000");
        assertThat(balanceOf(source))
                .describedAs("a refused transfer must leave the balance exactly as it was")
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("three consecutive refusals suspend the mandate")
    void repeatedRefusalsSuspend() {
        ScheduledTransferResponse created = schedule(ScheduleFrequency.DAILY, "5000.00", null);

        runnerOn(today).run();
        runnerOn(today.plusDays(1)).run();
        runnerOn(today.plusDays(2)).run();

        ScheduledTransferResponse after = scheduledTransfers.get(created.id());
        assertThat(after.status()).isEqualTo(ScheduleStatus.SUSPENDED);
        assertThat(after.nextRunOn()).isNull();

        // And a suspended mandate stays stopped, however many ticks go by.
        runnerOn(today.plusDays(3)).run();
        assertThat(scheduledTransfers.get(created.id()).runsCompleted()).isZero();
    }

    @Test
    @DisplayName("a cancelled mandate is never picked up again")
    void aCancelledMandateDoesNotRun() {
        ScheduledTransferResponse created = schedule(ScheduleFrequency.DAILY, "250.00", null);
        scheduledTransfers.cancel(created.id());

        runnerOn(today.plusDays(2)).run();

        assertThat(balanceOf(destination)).isEqualByComparingTo("0.00");
        assertThat(scheduledTransfers.get(created.id()).status()).isEqualTo(ScheduleStatus.CANCELLED);
    }

    @Test
    @DisplayName("a one-off mandate posts once and completes")
    void aOneOffCompletes() {
        ScheduledTransferResponse created = schedule(ScheduleFrequency.ONCE, "250.00", null);

        runnerOn(today).run();
        runnerOn(today.plusDays(5)).run();

        ScheduledTransferResponse after = scheduledTransfers.get(created.id());
        assertThat(after.status()).isEqualTo(ScheduleStatus.COMPLETED);
        assertThat(after.runsCompleted()).isEqualTo(1);
        assertThat(balanceOf(destination)).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("re-running an occurrence replays the posting instead of paying twice")
    void reRunningAnOccurrenceReplays() {
        // The crash-safety property, provoked directly: the money moved but the bookkeeping never
        // happened, so the mandate is still due on the same date. The derived idempotency key is
        // the only thing standing between that and a double payment.
        ScheduledTransferResponse created = schedule(ScheduleFrequency.DAILY, "250.00", null);

        var occurrence = scheduledTransfers.claim(created.id(), today).orElseThrow();
        scheduledTransfers.execute(occurrence);
        // ...and now the process dies before markSuccess. Next tick finds it due again.
        assertThat(scheduledTransfers.get(created.id()).nextRunOn()).isEqualTo(today);

        runnerOn(today).run();

        assertThat(balanceOf(destination))
                .describedAs("one payment, not two")
                .isEqualByComparingTo("250.00");
        ScheduledTransferResponse after = scheduledTransfers.get(created.id());
        assertThat(after.runsCompleted()).isEqualTo(1);
        assertThat(after.nextRunOn()).isEqualTo(today.plusDays(1));
    }

    @Test
    @DisplayName("bookkeeping for an occurrence already advanced does nothing")
    void advancingTwiceIsRefused() {
        // What two replicas racing looks like: both got through execute (the second replaying),
        // and both now try to advance. The second must be a no-op or an occurrence is skipped.
        ScheduledTransferResponse created = schedule(ScheduleFrequency.DAILY, "250.00", null);
        runnerOn(today).run();

        scheduledTransfers.markSuccess(created.id(), today);

        ScheduledTransferResponse after = scheduledTransfers.get(created.id());
        assertThat(after.runsCompleted()).isEqualTo(1);
        assertThat(after.nextRunOn()).isEqualTo(today.plusDays(1));
    }
}
