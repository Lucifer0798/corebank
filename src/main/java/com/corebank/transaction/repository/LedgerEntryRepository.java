package com.corebank.transaction.repository;

import com.corebank.transaction.domain.LedgerEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * Statement view for one account, newest first. The owning transaction is fetched in the
     * same query -- every statement row renders its type and description, so lazy-loading it
     * would mean one extra query per row.
     *
     * <p>Callers always pass concrete bounds; leaving them null would push an untyped null
     * parameter into the SQL, which PostgreSQL refuses to type.
     */
    @EntityGraph(attributePaths = "transaction")
    @Query(value = """
            select e from LedgerEntry e
             where e.account.id = :accountId
               and e.postedAt >= :from
               and e.postedAt <= :to
            """,
            countQuery = """
            select count(e) from LedgerEntry e
             where e.account.id = :accountId
               and e.postedAt >= :from
               and e.postedAt <= :to
            """)
    Page<LedgerEntry> findStatement(@Param("accountId") UUID accountId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    Pageable pageable);

    long countByAccountId(UUID accountId);

    /**
     * How much has genuinely left this account between two instants -- the figure the daily
     * velocity limit is measured against.
     *
     * <p>Summed from the ledger rather than kept in a counter column, unlike
     * {@code account.held_amount}. The trade is different here because the correction cases are:
     * a counter would have to be decremented when a withdrawal is reversed, and getting that
     * wrong means a customer silently losing allowance for a posting the bank itself undid.
     * Reading it from the entries makes those cases fall out of the two predicates below instead
     * of being extra code that can drift. The existing {@code idx_entry_account_posted} index
     * serves it, so the cost is one narrow indexed range scan inside the posting transaction.
     *
     * <p>{@code status = POSTED} excludes a withdrawal that was later reversed -- the money came
     * back, so it should not still be spending the day's allowance. {@code type <> REVERSAL}
     * excludes the correcting legs themselves: reversing a mistaken <em>deposit</em> debits the
     * customer, and that debit is the bank unwinding its own error, not the customer spending.
     */
    @Query("""
            select coalesce(sum(e.amount), 0) from LedgerEntry e
             where e.account.id = :accountId
               and e.direction = com.corebank.account.domain.EntryDirection.DEBIT
               and e.postedAt >= :from
               and e.postedAt < :to
               and e.transaction.status = com.corebank.transaction.domain.TransactionStatus.POSTED
               and e.transaction.type <> com.corebank.transaction.domain.TransactionType.REVERSAL
            """)
    BigDecimal sumDebitsBetween(@Param("accountId") UUID accountId,
                                @Param("from") Instant from,
                                @Param("to") Instant to);
}
