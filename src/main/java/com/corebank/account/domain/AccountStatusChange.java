package com.corebank.account.domain;

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
 * One change of an account's status: who froze, unfroze or closed it, from what, and why.
 *
 * <p>Immutable once written, as {@code KycDecision} is: no setters, no UPDATE from Hibernate, and no
 * delete on {@code AccountStatusChangeRepository}.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor
@Table(name = "account_status_change")
public class AccountStatusChange {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 20)
    private AccountStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private AccountStatus toStatus;

    @Column(name = "changed_by_subject", nullable = false, updatable = false, length = 64)
    private String changedBySubject;

    @Column(name = "changed_by_name", updatable = false, length = 255)
    private String changedByName;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    public AccountStatusChange(UUID accountId, AccountStatus fromStatus, AccountStatus toStatus, Actor changedBy,
                               String reason, Instant changedAt) {
        this.accountId = accountId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBySubject = changedBy.subject();
        this.changedByName = changedBy.name();
        this.reason = reason;
        this.changedAt = changedAt;
    }
}
