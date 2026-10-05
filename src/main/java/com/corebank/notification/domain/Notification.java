package com.corebank.notification.domain;

import com.corebank.account.domain.EntryDirection;
import com.corebank.transaction.domain.TransactionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Something a customer was told about money moving on one of their accounts.
 *
 * <p>Immutable once written, so it extends nothing that carries a version or an updated-at: a
 * notification records what was said at the time, and the right response to it being wrong is a
 * new one, never an edit to what the customer already read.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "notification")
public class Notification {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 40)
    private NotificationKind kind;

    /** The next three describe a posting, and are set only on a TRANSACTION notification. */
    @Column(name = "transaction_reference", updatable = false, length = 36)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_status", updatable = false, length = 20)
    private TransactionStatus transactionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", updatable = false, length = 10)
    private EntryDirection direction;

    /** The next two identify a missed standing-instruction occurrence, and are set only on those. */
    @Column(name = "scheduled_transfer_id", updatable = false)
    private UUID scheduledTransferId;

    @Column(name = "due_on", updatable = false)
    private LocalDate dueOn;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "message", nullable = false, updatable = false, length = 255)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
