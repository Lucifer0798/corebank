package com.corebank.transaction.domain;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.EntryDirection;
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
 * One leg of a posting. Entries are append-only: a correction is a new reversing
 * transaction, never an update to an existing row.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private BankTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private EntryDirection direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Running balance of the account after this entry, so statements need no recomputation. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    /** Signed amount from the account's own point of view: negative when the balance fell. */
    public BigDecimal signedAmount() {
        return direction == account.getNormalBalance() ? amount : amount.negate();
    }
}
