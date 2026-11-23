package com.corebank.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.common.metrics.BackgroundFailures;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.idempotency.IdempotencyService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.service.TransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the nightly retention job deletes, and what it must never touch.
 *
 * <p>Rows are planted with old timestamps; everything other tests write is recent, so the real
 * windows leave theirs alone in this shared database.
 */
@SpringBootTest
class RetentionTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private IdempotencyService idempotency;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private CustomerService customerService;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private RetentionService retention(int batchSize) {
        return new RetentionService(jdbc, Clock.systemUTC(), meters, Duration.ofDays(7), Duration.ofDays(10), batchSize);
    }

    private static Timestamp daysAgo(long days) {
        return Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
    }

    private UUID outboxEvent(Timestamp createdAt, Timestamp publishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into outbox_event (id, aggregate_type, topic, event_key, payload, created_at, published_at)
                values (?, 'TRANSACTION', 'corebank.transactions.posted', 'retention-test', '{}', ?, ?)
                """, id, createdAt, publishedAt);
        return id;
    }

    private UUID idempotencyRecord(String status, Timestamp createdAt, Timestamp completedAt, UUID claimToken) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into idempotency_record (id, scope, idempotency_key, request_hash, status, created_at,
                                                completed_at, claim_token)
                values (?, 'retention-test', ?, 'hash', ?, ?, ?, ?)
                """, id, "retention-" + id, status, createdAt, completedAt, claimToken);
        return id;
    }

    private boolean exists(String table, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where id = ?", Long.class, id) > 0;
    }

    @Test
    @DisplayName("published outbox events go after 7 days; an unpublished one never does, however old")
    void outboxRetention() {
        UUID oldPublished = outboxEvent(daysAgo(9), daysAgo(8));
        UUID recentPublished = outboxEvent(daysAgo(7), daysAgo(6));
        UUID oldUnpublished = outboxEvent(daysAgo(30), null);

        retention(1000).prune();

        assertThat(exists("outbox_event", oldPublished)).isFalse();
        assertThat(exists("outbox_event", recentPublished)).isTrue();
        // Still waiting to reach Kafka: deleting it would lose the event.
        assertThat(exists("outbox_event", oldUnpublished)).isTrue();
        jdbc.update("delete from outbox_event where id in (?, ?)", recentPublished, oldUnpublished);
    }

    @Test
    @DisplayName("completed keys go after 10 days, abandoned claims with a token too; a pre-token stuck claim is kept")
    void idempotencyRetention() {
        UUID oldCompleted = idempotencyRecord("COMPLETED", daysAgo(11), daysAgo(11), UUID.randomUUID());
        UUID recentCompleted = idempotencyRecord("COMPLETED", daysAgo(9), daysAgo(9), UUID.randomUUID());
        UUID oldAbandoned = idempotencyRecord("IN_PROGRESS", daysAgo(11), null, UUID.randomUUID());
        UUID recentClaim = idempotencyRecord("IN_PROGRESS", daysAgo(0), null, UUID.randomUUID());
        UUID oldWithoutToken = idempotencyRecord("IN_PROGRESS", daysAgo(30), null, null);

        retention(1000).prune();

        assertThat(exists("idempotency_record", oldCompleted)).isFalse();
        assertThat(exists("idempotency_record", recentCompleted)).isTrue();
        // Changed nothing: completion commits with the operation, and this one never completed.
        assertThat(exists("idempotency_record", oldAbandoned)).isFalse();
        assertThat(exists("idempotency_record", recentClaim)).isTrue();
        // From before claim tokens: it may have posted, so it is a person's to look at.
        assertThat(exists("idempotency_record", oldWithoutToken)).isTrue();
        jdbc.update("delete from idempotency_record where id in (?, ?, ?)", recentCompleted, recentClaim, oldWithoutToken);
    }

    @Test
    @DisplayName("a backlog bigger than one batch is all deleted, a batch at a time")
    void deletesEveryBatch() {
        List<UUID> old = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            old.add(outboxEvent(daysAgo(20), daysAgo(19)));
        }

        RetentionService.Pruned pruned = retention(2).prune();

        assertThat(old).noneMatch(id -> exists("outbox_event", id));
        assertThat(pruned.outboxEvents()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("what is deleted is counted, by table, from zero")
    void deletionsAreCounted() {
        RetentionService service = retention(1000);
        assertThat(meters.get(RetentionService.METRIC).tag("table", "outbox_event").counter().count()).isZero();
        assertThat(meters.get(RetentionService.METRIC).tag("table", "idempotency_record").counter().count()).isZero();

        outboxEvent(daysAgo(9), daysAgo(8));
        idempotencyRecord("COMPLETED", daysAgo(11), daysAgo(11), UUID.randomUUID());
        RetentionService.Pruned pruned = service.prune();

        assertThat(meters.get(RetentionService.METRIC).tag("table", "outbox_event").counter().count())
                .isEqualTo(pruned.outboxEvents()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get(RetentionService.METRIC).tag("table", "idempotency_record").counter().count())
                .isEqualTo(pruned.idempotencyRecords()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the API contract: a retry within 10 days replays, one with a key completed longer ago posts again")
    void anExpiredKeyPostsAgain() {
        UUID customer = customerService.create(new CreateCustomerRequest("Retention", "Holder",
                "retention." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customer, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        UUID account = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        AmountRequest deposit = new AmountRequest(new BigDecimal("10.00"), "INR", "Retention");
        String key = "retention-" + UUID.randomUUID();
        String scope = "deposit:" + account;
        java.util.function.Supplier<IdempotencyService.Result<TransactionResponse>> send = () -> idempotency.execute(
                scope, key, deposit, TransactionResponse.class, () -> transactionService.deposit(account, deposit, key));

        assertThat(send.get().replayed()).isFalse();
        jdbc.update("update idempotency_record set completed_at = ? where scope = ? and idempotency_key = ?",
                daysAgo(9), scope, key);
        retention(1000).prune();
        assertThat(send.get().replayed()).as("nine days on").isTrue();
        assertThat(accountService.get(account).balance()).isEqualByComparingTo("10.00");

        jdbc.update("update idempotency_record set completed_at = ? where scope = ? and idempotency_key = ?",
                daysAgo(11), scope, key);
        retention(1000).prune();
        assertThat(send.get().replayed()).as("eleven days on").isFalse();
        assertThat(accountService.get(account).balance()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("a night it cannot run is counted, and does not escape the runner")
    void aFailedRunIsCounted() {
        RetentionService failing = mock(RetentionService.class);
        when(failing.prune()).thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetentionRunner runner = new RetentionRunner(failing, registry);
        assertThat(registry.get(BackgroundFailures.METRIC).tag("task", "retention").counter().count()).isZero();

        runner.run();

        assertThat(registry.get(BackgroundFailures.METRIC).tag("task", "retention").counter().count()).isEqualTo(1);
    }
}
