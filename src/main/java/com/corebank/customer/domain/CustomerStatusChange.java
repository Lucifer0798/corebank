package com.corebank.customer.domain;

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

/**
 * One suspension, reinstatement or closure of a customer: who, when and why. Written in the same
 * transaction as the change itself, and never altered afterwards.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor
@Table(name = "customer_status_change")
public class CustomerStatusChange {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 20)
    private CustomerStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private CustomerStatus toStatus;

    @Column(name = "changed_by_subject", nullable = false, updatable = false, length = 64)
    private String changedBySubject;

    @Column(name = "changed_by_name", updatable = false, length = 255)
    private String changedByName;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    /**
     * Numbered by the database as the row is inserted: the tiebreak when two entries share a
     * timestamp, so newest first is the order they were written in. See V21. Only queries use it,
     * so it is not read back after a save, and is null on an entity that has just been saved.
     */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    public CustomerStatusChange(UUID customerId, CustomerStatus fromStatus, CustomerStatus toStatus, Actor changedBy,
                                String reason, Instant changedAt) {
        this.customerId = customerId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBySubject = changedBy.subject();
        this.changedByName = changedBy.name();
        this.reason = reason;
        this.changedAt = changedAt;
    }
}
