package com.corebank.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deletes rows nothing will read again. Two tables grew by a row per request and never shrank:
 *
 * <ul>
 *   <li><b>outbox_event</b>: once an event is published, nothing reads its row again. The relay and
 *       the backlog monitor only look at unpublished ones, and the admin replay re-derives events
 *       from the ledger, not from here. Published rows older than {@code outbox-after} (7 days)
 *       go; an unpublished one never does, however old.</li>
 *   <li><b>idempotency_record</b>: a completed key is what makes a retry replay instead of posting
 *       again, so deleting it is part of the API contract: a retry with a key completed more than
 *       {@code idempotency-after} (10 days) ago posts again. An abandoned claim that carries a token
 *       went the same way: under the rule {@code IdempotencyService} completes by, it changed
 *       nothing. A stuck claim with no token, from before that rule, may have posted, and is kept
 *       for a person to look at.</li>
 * </ul>
 *
 * <p>In batches, each its own short statement, so the first run against a table that has grown for
 * years never holds a long lock or one huge transaction.
 */
@Service
public class RetentionService {

    static final String METRIC = "corebank.retention.deleted";

    private static final String OUTBOX = """
            DELETE FROM outbox_event WHERE id IN (
                SELECT id FROM outbox_event
                 WHERE published_at IS NOT NULL AND published_at < ?
                 LIMIT ?)
            """;

    private static final String COMPLETED_KEYS = """
            DELETE FROM idempotency_record WHERE id IN (
                SELECT id FROM idempotency_record
                 WHERE status = 'COMPLETED' AND completed_at < ?
                 LIMIT ?)
            """;

    private static final String ABANDONED_CLAIMS = """
            DELETE FROM idempotency_record WHERE id IN (
                SELECT id FROM idempotency_record
                 WHERE status = 'IN_PROGRESS' AND completed_at IS NULL AND claim_token IS NOT NULL
                   AND created_at < ?
                 LIMIT ?)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration outboxAfter;
    private final Duration idempotencyAfter;
    private final int batchSize;
    private final Counter outboxDeleted;
    private final Counter keysDeleted;

    public RetentionService(JdbcTemplate jdbc, Clock clock, MeterRegistry meterRegistry,
                            @Value("${corebank.retention.outbox-after:7d}") Duration outboxAfter,
                            @Value("${corebank.retention.idempotency-after:10d}") Duration idempotencyAfter,
                            @Value("${corebank.retention.batch-size:1000}") int batchSize) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.outboxAfter = outboxAfter;
        this.idempotencyAfter = idempotencyAfter;
        this.batchSize = batchSize;
        // From zero, like every other counter an alert or a dashboard might watch.
        this.outboxDeleted = deletedFrom("outbox_event", meterRegistry);
        this.keysDeleted = deletedFrom("idempotency_record", meterRegistry);
    }

    private static Counter deletedFrom(String table, MeterRegistry meterRegistry) {
        return Counter.builder(METRIC)
                .description("Rows the nightly retention job deleted, by table")
                .tag("table", table)
                .register(meterRegistry);
    }

    public Pruned prune() {
        Instant now = clock.instant();
        long outbox = deleteInBatches(OUTBOX, now.minus(outboxAfter));
        Instant keysBefore = now.minus(idempotencyAfter);
        long keys = deleteInBatches(COMPLETED_KEYS, keysBefore) + deleteInBatches(ABANDONED_CLAIMS, keysBefore);
        outboxDeleted.increment(outbox);
        keysDeleted.increment(keys);
        return new Pruned(outbox, keys);
    }

    private long deleteInBatches(String sql, Instant before) {
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, Timestamp.from(before), batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }

    /** How many rows went from each table. */
    public record Pruned(long outboxEvents, long idempotencyRecords) {
    }
}
