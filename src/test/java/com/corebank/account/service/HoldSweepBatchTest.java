package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.HoldStatus;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every expired hold in one sweep, however many there are.
 *
 * <p>A sweep used to take the first 50 and stop, so expiry reached at most 50 holds every five
 * minutes. Past that, expired holds piled up and kept customers' money reserved. The first page was
 * always the same, too, so 50 holds that failed every time would have stopped the sweep for good.
 */
class HoldSweepBatchTest {

    /** More than one batch, so the second one has to be fetched. */
    private static final int MORE_THAN_A_BATCH = HoldService.SWEEP_BATCH_SIZE + 5;

    @Nested
    @DisplayName("against the real database")
    @SpringBootTest
    class AgainstTheDatabase {

        @Autowired private HoldService holdService;
        @Autowired private AccountService accountService;
        @Autowired private CustomerService customerService;
        @Autowired private TransactionService transactionService;
        @Autowired private JdbcTemplate jdbc;

        private UUID account;

        /** Holds on one account, every one of them past its expiry. */
        private List<UUID> expiredHolds(int count) {
            UUID customer = customerService.create(new CreateCustomerRequest("Sweep", "Holder",
                    "sweep." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
            customerService.updateKyc(customer, KycStatus.VERIFIED, null, TestDeciders.STAFF);
            account = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
            transactionService.deposit(account, new AmountRequest(new BigDecimal("1000.00"), "INR", "Funding"),
                    "sweep-" + UUID.randomUUID());

            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(holdService.place(account,
                        new PlaceHoldRequest(new BigDecimal("1.00"), "INR", "Hold " + i, 24)).id());
            }
            // Placed two minutes ago, expired one minute ago: a hold must expire after it is placed.
            Timestamp placed = Timestamp.from(Instant.now().minusSeconds(120));
            Timestamp expired = Timestamp.from(Instant.now().minusSeconds(60));
            for (UUID id : ids) {
                jdbc.update("update account_hold set placed_at = ?, expires_at = ? where id = ?", placed, expired, id);
            }
            return ids;
        }

        @Test
        @DisplayName("one sweep expires every expired hold, not just the first batch")
        void oneSweepExpiresEveryHold() {
            List<UUID> ids = expiredHolds(MORE_THAN_A_BATCH);

            new HoldExpiryRunner(holdService, new SimpleMeterRegistry()).sweep();

            assertThat(ids).allSatisfy(id -> assertThat(holdService.get(holdReference(id)).status())
                    .isEqualTo(HoldStatus.EXPIRED));
            assertThat(accountService.get(account).heldAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("paging after the last id seen reaches every expired hold exactly once")
        void pagingReachesEveryHoldOnce() {
            // The query's own job, so it is checked against the database: a query that ignored
            // "after" would hand back the first page for ever, and only a real one can show that.
            List<UUID> ids = expiredHolds(MORE_THAN_A_BATCH);

            List<UUID> seen = new ArrayList<>();
            UUID after = HoldService.FIRST;
            for (int page = 0; page < 1_000; page++) {
                List<UUID> batch = holdService.findExpired(after);
                if (batch.isEmpty()) {
                    break;
                }
                assertThat(batch).hasSizeLessThanOrEqualTo(HoldService.SWEEP_BATCH_SIZE);
                seen.addAll(batch);
                after = batch.getLast();
            }

            assertThat(seen).doesNotHaveDuplicates().containsAll(ids);
        }

        private String holdReference(UUID id) {
            return jdbc.queryForObject("select reference from account_hold where id = ?", String.class, id);
        }
    }

    @Nested
    @DisplayName("when holds fail")
    class WhenHoldsFail {

        /**
         * A full batch of holds that fail every time, then healthy ones. Each fetch is a page of
         * expired holds after {@code after}, in a fixed order, and an expired hold leaves the
         * results: what the real query returns.
         */
        private final List<UUID> failing = IntStream.range(0, HoldService.SWEEP_BATCH_SIZE)
                .mapToObj(i -> UUID.randomUUID()).toList();
        private final List<UUID> healthy = IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID()).toList();
        private final List<UUID> all = new ArrayList<>(failing);
        private final Set<UUID> done = ConcurrentHashMap.newKeySet();

        WhenHoldsFail() {
            all.addAll(healthy);
        }

        private List<UUID> pageAfter(UUID after) {
            int from = after.equals(HoldService.FIRST) ? 0 : all.indexOf(after) + 1;
            return all.subList(from, all.size()).stream()
                    .filter(id -> !done.contains(id))
                    .limit(HoldService.SWEEP_BATCH_SIZE)
                    .toList();
        }

        @Test
        @DisplayName("a full batch of holds that always fail does not stop the rest being freed")
        void failuresDoNotBlockTheSweep() {
            HoldService holds = mock(HoldService.class);
            when(holds.findExpired(any())).thenAnswer(call -> pageAfter(call.getArgument(0)));
            doAnswer(call -> {
                UUID id = call.getArgument(0);
                if (failing.contains(id)) {
                    throw new IllegalStateException("hold " + id + " always fails");
                }
                done.add(id);
                return null;
            }).when(holds).expire(any());

            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> new HoldExpiryRunner(holds, new SimpleMeterRegistry()).sweep());

            assertThat(done).containsExactlyInAnyOrderElementsOf(healthy);
        }
    }
}
