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
import java.util.Map;
import java.util.TreeMap;
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

    /**
     * The rate applied, on a cross-currency posting only. Eight decimal places because a rate is
     * not money: rounded to the money scale, a large conversion could not be reproduced from its
     * own audit trail.
     */
    @Column(name = "exchange_rate", precision = 19, scale = 8, updatable = false)
    private BigDecimal exchangeRate;

    /** What {@code amount} became in the other currency, so a statement need not re-derive it. */
    @Column(name = "counter_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal counterAmount;

    @Column(name = "counter_currency", length = 3, updatable = false)
    private String counterCurrency;

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
     * The postings a reversal may undo: movements a customer or teller initiated.
     *
     * <p>An allow-list, deliberately, where it used to be a single block on REVERSAL. That left
     * INTEREST and FX_REVALUATION reversible by default, simply because nobody had thought to forbid
     * them, and both were wrong to reverse:
     *
     * <ul>
     *   <li>Reversing <strong>interest</strong> destroyed it. Capitalising moves the amount out of
     *       {@code accrued_interest} and into the balance; a reversal took it back out of the
     *       balance and restored nothing, so the interest vanished from both places at once.
     *   <li>Reversing a <strong>revaluation</strong> broke the invariant that the revaluation
     *       account's balance is the mark. A wrong mark is corrected by running the close again,
     *       which posts the delta back to where the market says it should be.
     * </ul>
     *
     * <p>Both are system postings with their own correction path, and the general rule is that the
     * process which produced a posting is the one that corrects it. Listing what <em>can</em> be
     * reversed means the next posting type anyone adds is non-reversible until someone decides
     * otherwise, rather than reversible until someone notices.
     */
    private static final java.util.Set<TransactionType> REVERSIBLE_TYPES = java.util.EnumSet.of(
            TransactionType.DEPOSIT, TransactionType.WITHDRAWAL, TransactionType.TRANSFER);

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
        if (!REVERSIBLE_TYPES.contains(type)) {
            throw new BusinessRuleException("NOT_REVERSIBLE",
                    "A " + type + " posting is corrected by the process that produced it, not by reversal");
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
     * Double-entry invariant: debits must equal credits <em>in every currency separately</em>.
     * Checked before the transaction is written so an unbalanced posting can never reach the ledger.
     *
     * <p>Per currency, not in total, and the difference only started to matter with FX. A
     * cross-currency transfer has four legs -- the customer debited in one currency, an FX
     * position account credited in the same currency, that position debited in the other, the
     * destination credited in the other -- and summing all four together would balance by pure
     * coincidence: the two amounts appear once on each side, so any pair of numbers passes. It
     * would happily accept 1,000 rupees turning into 1,000,000 dollars.
     *
     * <p>Grouping by currency first means each side of the trade has to balance against its own
     * position leg, which is the only sense in which a multi-currency posting can be said to
     * balance at all. Adding rupees to dollars is not arithmetic.
     */
    public void assertBalanced() {
        Map<String, BigDecimal> netByCurrency = new TreeMap<>();
        for (LedgerEntry entry : entries) {
            BigDecimal signed = entry.getDirection() == EntryDirection.DEBIT
                    ? entry.getAmount()
                    : entry.getAmount().negate();
            netByCurrency.merge(entry.getAccount().getCurrency(), signed, BigDecimal::add);
        }

        for (Map.Entry<String, BigDecimal> net : netByCurrency.entrySet()) {
            if (net.getValue().compareTo(BigDecimal.ZERO) != 0) {
                throw new BusinessRuleException("UNBALANCED_POSTING",
                        "Debits and credits do not balance in " + net.getKey()
                                + " (net " + net.getValue() + ")");
            }
        }
    }
}
