package com.corebank.schedule.domain;

import com.corebank.common.security.Actor;
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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** One thing that happened to a standing instruction, and who did it. Immutable, like every history here. */
@Getter
@Entity
@Immutable
@NoArgsConstructor
@Table(name = "scheduled_transfer_event")
public class ScheduledTransferEvent {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scheduled_transfer_id", nullable = false, updatable = false)
    private UUID scheduledTransferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, updatable = false, length = 20)
    private ScheduleEventType event;

    @Column(name = "actor_subject", nullable = false, updatable = false, length = 64)
    private String actorSubject;

    @Column(name = "actor_name", updatable = false, length = 255)
    private String actorName;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public ScheduledTransferEvent(UUID scheduledTransferId, ScheduleEventType event, Actor actor, String reason,
                                  Instant occurredAt) {
        this.scheduledTransferId = scheduledTransferId;
        this.event = event;
        this.actorSubject = actor.subject();
        this.actorName = actor.name();
        this.reason = reason;
        this.occurredAt = occurredAt;
    }
}
