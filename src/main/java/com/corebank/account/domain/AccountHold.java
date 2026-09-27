package com.corebank.account.domain;

import com.corebank.common.domain.AuditableEntity;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * An authorisation hold: money promised to a merchant but not yet moved.
 *
 * <p>A hold is deliberately <em>not</em> a ledger entry. Nothing has happened to the bank's
 * position -- the customer still owns the money and the bank still owes it -- so posting one
 * would put a movement in the ledger that never occurred. What it does instead is reserve the
 * amount against {@link Account#availableBalance()}, and only a capture turns it into a posting.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "account_hold")
public class AccountHold extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Client-facing handle, the way a transaction has a reference. */
    @Column(name = "reference", nullable = false, updatable = false, length = 36)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "description", length = 255, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private HoldStatus status = HoldStatus.ACTIVE;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    /** When an uncaptured hold stops reserving anything. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    /**
     * The posting a capture produced. Null on everything else -- a released or expired hold moved
     * no money, and that absence is the record of it.
     */
    @Column(name = "captured_transaction_reference", length = 36)
    private String capturedTransactionReference;

    /**
     * Whether this hold can still be acted on, and if not, why not.
     *
     * <p>Expiry is checked against the clock rather than trusting {@code status}: the sweep that
     * marks holds EXPIRED runs on an interval, so between the expiry instant and the next tick a
     * hold is still ACTIVE in the database while being, in every sense that matters, over. A
     * capture arriving in that window must be refused, not honoured because a background job
     * happened not to have run yet.
     */
    public void assertCapturable(Instant now) {
        if (status != HoldStatus.ACTIVE) {
            throw new ConflictException("HOLD_NOT_ACTIVE",
                    "Hold " + reference + " has already been " + status.name().toLowerCase());
        }
        if (!now.isBefore(expiresAt)) {
            throw new BusinessRuleException("HOLD_EXPIRED",
                    "Hold " + reference + " expired at " + expiresAt);
        }
    }

    public void settle(HoldStatus outcome, Instant at, String transactionReference) {
        this.status = outcome;
        this.settledAt = at;
        this.capturedTransactionReference = transactionReference;
    }

    public boolean hasExpiredBy(Instant now) {
        return status == HoldStatus.ACTIVE && !now.isBefore(expiresAt);
    }
}
