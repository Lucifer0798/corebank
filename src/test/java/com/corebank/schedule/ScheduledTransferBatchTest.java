package com.corebank.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.config.CoreBankProperties;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.service.ScheduledTransferService;
import com.corebank.schedule.service.ScheduledTransferService.DueOccurrence;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.service.TransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every due standing order in one tick, however many there are, and still one occurrence each.
 *
 * <p>A tick used to take the first 50 and stop, so standing orders ran at most 50 a minute and a
 * busy 1st paid late. The first page was always the same, too, so 50 mandates that failed every
 * time would have stopped the runner for good.
 */
class ScheduledTransferBatchTest {

    @Nested
    @DisplayName("against the real database")
    @SpringBootTest
    class AgainstTheDatabase {

        @Autowired private ScheduledTransferService scheduledTransfers;
        @Autowired private AccountService accountService;
        @Autowired private CustomerService customerService;
        @Autowired private TransactionService transactionService;
        @Autowired private CoreBankProperties properties;

        private final LocalDate today = LocalDate.now(Clock.systemUTC());
        private final List<UUID> created = new ArrayList<>();
        private UUID destination;

        /** Left active, these would run under every later test that runs the runner. */
        @AfterEach
        void cancelThem() {
            created.forEach(id -> scheduledTransfers.cancel(id, null));
        }

        private int moreThanABatch() {
            return properties.scheduledTransfers().batchSize() + 5;
        }

        /** Daily mandates of 1.00 between two accounts, each due from today. */
        private List<UUID> dailyMandates(int count) {
            UUID customer = customerService.create(new CreateCustomerRequest("Batch", "Payer",
                    "batch.payer." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
            customerService.updateKyc(customer, KycStatus.VERIFIED, null, TestDeciders.STAFF);
            UUID source = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
            destination = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
            transactionService.deposit(source, new AmountRequest(new BigDecimal("1000.00"), "INR", "Funding"),
                    "batch-" + UUID.randomUUID());
            for (int i = 0; i < count; i++) {
                created.add(scheduledTransfers.create(new CreateScheduledTransferRequest(source, destination,
                        new BigDecimal("1.00"), "INR", "Batch " + i, ScheduleFrequency.DAILY, today, null)).id());
            }
            return List.copyOf(created);
        }

        private ScheduledTransferRunner runnerOn(LocalDate date) {
            return new ScheduledTransferRunner(scheduledTransfers, new SimpleMeterRegistry(),
                    Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        }

        @Test
        @DisplayName("one tick reaches every due mandate, and posts one occurrence of each however far behind")
        void oneTickReachesEveryMandateOnce() {
            List<UUID> ids = dailyMandates(moreThanABatch());

            // Three days late: each mandate is four occurrences behind, and is still due after it
            // posts one. Paging by the due date would meet it again further on.
            runnerOn(today.plusDays(3)).run();

            assertThat(ids).allSatisfy(id -> assertThat(scheduledTransfers.get(id).runsCompleted())
                    .as("occurrences posted this tick").isEqualTo(1));
            assertThat(accountService.get(destination).balance())
                    .isEqualByComparingTo(new BigDecimal(moreThanABatch()));
        }

        @Test
        @DisplayName("paging after the last id seen reaches every due mandate exactly once")
        void pagingReachesEveryMandateOnce() {
            List<UUID> ids = dailyMandates(moreThanABatch());

            List<UUID> seen = new ArrayList<>();
            UUID after = ScheduledTransferService.FIRST;
            for (int page = 0; page < 1_000; page++) {
                List<UUID> batch = scheduledTransfers.findDue(today, after);
                if (batch.isEmpty()) {
                    break;
                }
                assertThat(batch).hasSizeLessThanOrEqualTo(properties.scheduledTransfers().batchSize());
                seen.addAll(batch);
                after = batch.getLast();
            }

            assertThat(seen).doesNotHaveDuplicates().containsAll(ids);
        }
    }

    @Nested
    @DisplayName("when mandates fail")
    class WhenMandatesFail {

        private static final int BATCH = 50;
        private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

        /**
         * A full batch of mandates the runner cannot process, then healthy ones. Each fetch is a page
         * of due mandates after {@code after}, in a fixed order, and one that has posted is no longer
         * due: what the real query returns.
         */
        private final List<UUID> failing = IntStream.range(0, BATCH).mapToObj(i -> UUID.randomUUID()).toList();
        private final List<UUID> healthy = IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID()).toList();
        private final List<UUID> all = new ArrayList<>(failing);
        private final Set<UUID> done = ConcurrentHashMap.newKeySet();

        WhenMandatesFail() {
            all.addAll(healthy);
        }

        private List<UUID> pageAfter(UUID after) {
            int from = after.equals(ScheduledTransferService.FIRST) ? 0 : all.indexOf(after) + 1;
            return all.subList(from, all.size()).stream()
                    .filter(id -> !done.contains(id))
                    .limit(BATCH)
                    .toList();
        }

        private static DueOccurrence due(UUID id) {
            return new DueOccurrence(id, TODAY, "sched:" + id + ":" + TODAY,
                    new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "Rent"));
        }

        @Test
        @DisplayName("a full batch of mandates that always fail does not stop the rest being paid")
        void failuresDoNotBlockTheRun() {
            ScheduledTransferService service = mock(ScheduledTransferService.class);
            when(service.findDue(eq(TODAY), any())).thenAnswer(call -> pageAfter(call.getArgument(1)));
            when(service.claim(any(), eq(TODAY))).thenAnswer(call -> {
                UUID id = call.getArgument(0);
                if (failing.contains(id)) {
                    throw new IllegalStateException("mandate " + id + " always fails");
                }
                return Optional.of(due(id));
            });
            when(service.execute(any())).thenAnswer(call -> {
                done.add(((DueOccurrence) call.getArgument(0)).scheduleId());
                return null;
            });

            ScheduledTransferRunner runner = new ScheduledTransferRunner(service, new SimpleMeterRegistry(),
                    Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
            assertTimeoutPreemptively(Duration.ofSeconds(10), runner::run);

            assertThat(done).containsExactlyInAnyOrderElementsOf(healthy);
        }
    }
}
