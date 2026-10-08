package com.corebank.customer.domain;

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
 * One KYC decision: who moved a customer from what to what, and why.
 *
 * <p>Immutable once written. There are no setters, Hibernate is told never to issue an UPDATE for
 * it, and {@code KycDecisionRepository} has no delete. A history that can be edited is not a
 * history.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor
@Table(name = "kyc_decision")
public class KycDecision {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 20)
    private KycStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private KycStatus toStatus;

    @Column(name = "decided_by_subject", nullable = false, updatable = false, length = 64)
    private String decidedBySubject;

    @Column(name = "decided_by_name", updatable = false, length = 255)
    private String decidedByName;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    public KycDecision(UUID customerId, KycStatus fromStatus, KycStatus toStatus, KycDecider decidedBy,
                       String reason, Instant decidedAt) {
        this.customerId = customerId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.decidedBySubject = decidedBy.subject();
        this.decidedByName = decidedBy.name();
        this.reason = reason;
        this.decidedAt = decidedAt;
    }
}
