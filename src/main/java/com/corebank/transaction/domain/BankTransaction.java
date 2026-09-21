package com.corebank.transaction.domain;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.EntryDirection;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ConflictException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * A balanced set of ledger entries recorded as one business event.
 * {@code transaction} is reserved in several dialects, so the table is named {@code bank_transaction}.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Client-facing handle for the transaction, safe to print on a receipt. */
    @Column(name = "reference", nullable = false, updatable = false, length = 36)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.POSTED;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "idempotency_key", updatable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Set only on a {@link TransactionType#REVERSAL}, naming the posting it undoes. The original
     * carries no pointer back: it records that it was reversed in its {@code status}, and the
     * reversal is found by looking for the row that names it. A unique constraint on this column
     * is what actually stops a transaction being reversed twice -- see {@code V6}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_transaction_id", updatable = false)
    private BankTransaction reversalOf;

    @OrderBy("sequenceNo ASC")
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerEntry> entries = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Adds a leg, updating the account balance and recording the balance it left behind. */
    public LedgerEntry addEntry(Account account, EntryDirection direction, BigDecimal amount) {
        BigDecimal balanceAfter = account.applyEntry(direction, amount);

        LedgerEntry entry = new LedgerEntry();
        entry.setTransaction(this);
        entry.setAccount(account);
        entry.setDirection(direction);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        entry.setSequenceNo(entries.size() + 1);
        entry.setPostedAt(postedAt);
        entries.add(entry);
        return entry;
    }

    /**
     * Adds a leg that undoes one of {@code original}'s: same account, same amount, opposite
     * direction. Because the mirrored legs are added in the original's own order, the reversal's
     * legs line up one-for-one with what they cancel.
     *
     * <p>Unlike {@link #addEntry}, this may take a customer account past its overdraft limit --
     * see {@link Account#applyEntry(EntryDirection, java.math.BigDecimal, boolean)} for why a
     * correction is allowed to do that when an ordinary posting is not.
     */
    public LedgerEntry addReversingEntry(LedgerEntry original) {
        EntryDirection mirrored = original.getDirection() == EntryDirection.DEBIT
                ? EntryDirection.CREDIT
                : EntryDirection.DEBIT;
        Account account = original.getAccount();
        BigDecimal balanceAfter = account.applyEntry(mirrored, original.getAmount(), true);

        LedgerEntry entry = new LedgerEntry();
        entry.setTransaction(this);
        entry.setAccount(account);
        entry.setDirection(mirrored);
        entry.setAmount(original.getAmount());
        entry.setBalanceAfter(balanceAfter);
        entry.setSequenceNo(entries.size() + 1);
        entry.setPostedAt(postedAt);
        entries.add(entry);
        return entry;
    }

    /**
     * Whether this posting may be reversed, and if not, why not. The two refusals mean different
     * things to a caller, so they are different exceptions rather than one:
     *
     * <ul>
     *   <li>a reversal is not reversible <em>at all</em>, in any circumstances -- retrying will
     *       never help, so it is a rule violation (422);
     *   <li>an already-reversed transaction was reversible a moment ago and no longer is -- the
     *       caller's view of the state is simply stale, which is a conflict (409). It is also
     *       what a duplicate request looks like when the client retried without an
     *       {@code Idempotency-Key}.
     * </ul>
     */
    public void assertReversible() {
        if (type == TransactionType.REVERSAL) {
            throw new BusinessRuleException("REVERSAL_NOT_REVERSIBLE",
                    "Transaction " + reference + " is itself a reversal and cannot be reversed;"
                            + " post the original movement again instead");
        }
        if (status == TransactionStatus.REVERSED) {
            throw new ConflictException("ALREADY_REVERSED",
                    "Transaction " + reference + " has already been reversed");
        }
    }

    /** Records that a correcting transaction has undone this one. */
    public void markReversed() {
        this.status = TransactionStatus.REVERSED;
    }

    /**
     * Double-entry invariant: total debits must equal total credits.
     * Checked before the transaction is written so an unbalanced posting can never reach the ledger.
     */
    public void assertBalanced() {
        BigDecimal debits = sum(EntryDirection.DEBIT);
        BigDecimal credits = sum(EntryDirection.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new BusinessRuleException("UNBALANCED_POSTING",
                    "Debits (" + debits + ") do not equal credits (" + credits + ")");
        }
    }

    private BigDecimal sum(EntryDirection direction) {
        return entries.stream()
                .filter(entry -> entry.getDirection() == direction)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
