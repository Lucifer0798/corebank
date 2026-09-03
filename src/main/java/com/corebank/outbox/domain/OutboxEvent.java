package com.corebank.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * One event this application still owes Kafka. Written by {@code OutboxEventWriter} inside the
 * same transaction as the ledger or customer change it describes, so it is durable the instant
 * that change commits -- unlike the direct-to-Kafka send this replaced, which lost the event
 * permanently if the broker happened to be unreachable at that exact moment. {@code OutboxRelay}
 * is the only thing that ever reads {@code publishedAt IS NULL} rows and actually talks to Kafka.
 *
 * <p>No {@code AuditableEntity}: this row is mutated by the relay under a pessimistic lock, not
 * by a request handler, so an optimistic-lock {@code @Version} would only fight that lock rather
 * than protect anything.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 30)
    private OutboxAggregateType aggregateType;

    @Column(name = "topic", nullable = false, updatable = false, length = 100)
    private String topic;

    @Column(name = "event_key", nullable = false, updatable = false, length = 80)
    private String eventKey;

    /** The event, already serialized to JSON -- exactly the bytes the relay sends verbatim. */
    @Column(name = "payload", nullable = false, updatable = false, length = 4000)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public static OutboxEvent create(OutboxAggregateType aggregateType, String topic, String eventKey,
                                      String payload) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.topic = topic;
        event.eventKey = eventKey;
        event.payload = payload;
        event.createdAt = Instant.now();
        return event;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        // Kept short deliberately -- this is a diagnostic breadcrumb in a NOT NULL-adjacent
        // column sized like the rest of this schema's short text fields, not a stack trace store.
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }
}
