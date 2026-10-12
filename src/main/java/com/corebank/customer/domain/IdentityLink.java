package com.corebank.customer.domain;

import com.corebank.common.security.Actor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * One link of a sign-in identity to a customer: a first link, or a relink with the login it
 * replaced. Immutable once written, like the other histories.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor
@Table(name = "identity_link")
public class IdentityLink {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "previous_subject", updatable = false, length = 64)
    private String previousSubject;

    @Column(name = "new_subject", nullable = false, updatable = false, length = 64)
    private String newSubject;

    @Column(name = "linked_by_subject", nullable = false, updatable = false, length = 64)
    private String linkedBySubject;

    @Column(name = "linked_by_name", updatable = false, length = 255)
    private String linkedByName;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    public IdentityLink(UUID customerId, String previousSubject, String newSubject, Actor linkedBy, String reason,
                        Instant linkedAt) {
        this.customerId = customerId;
        this.previousSubject = previousSubject;
        this.newSubject = newSubject;
        this.linkedBySubject = linkedBy.subject();
        this.linkedByName = linkedBy.name();
        this.reason = reason;
        this.linkedAt = linkedAt;
    }
}
