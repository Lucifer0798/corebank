package com.corebank.transaction.domain;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.EntryDirection;
import com.corebank.common.exception.BusinessRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
