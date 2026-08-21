package com.corebank.transaction.repository;

import com.corebank.transaction.domain.LedgerEntry;
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
}
