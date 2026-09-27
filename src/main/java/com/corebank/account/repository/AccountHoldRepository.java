package com.corebank.account.repository;

import com.corebank.account.domain.AccountHold;
import com.corebank.account.domain.HoldStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface AccountHoldRepository extends JpaRepository<AccountHold, UUID> {

    Optional<AccountHold> findByReference(String reference);

    /**
     * Locks one hold for settlement. {@code SKIP LOCKED} is wrong here and deliberately absent:
     * a capture that cannot get the lock must wait for whatever is settling the hold and then see
     * the result, not skip it and report success having done nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM AccountHold h WHERE h.reference = :reference")
    Optional<AccountHold> lockByReference(@Param("reference") String reference);

    Page<AccountHold> findByAccountIdOrderByPlacedAtDesc(UUID accountId, Pageable pageable);

    /**
     * Ids of holds the sweep should expire. Ids rather than entities: each is then re-read and
     * locked in its own transaction, so one failure cannot roll back the rest of the batch --
     * the same shape {@code ScheduledTransferService} uses, for the same reason.
     */
    @Query("""
            SELECT h.id FROM AccountHold h
            WHERE h.status = :status AND h.expiresAt <= :now
            ORDER BY h.expiresAt ASC
            """)
    List<UUID> findExpiredIds(@Param("status") HoldStatus status, @Param("now") Instant now, Pageable pageable);

    /**
     * What the account's held_amount column ought to be. Nothing in the running application reads
     * this -- the column is maintained incrementally -- but a reconciliation that cannot be
     * expressed has to be taken on trust, and this is the one assertion that proves the
     * denormalised total never drifted from the holds behind it.
     */
    @Query("""
            SELECT COALESCE(SUM(h.amount), 0) FROM AccountHold h
            WHERE h.account.id = :accountId AND h.status = com.corebank.account.domain.HoldStatus.ACTIVE
            """)
    java.math.BigDecimal sumOutstandingFor(@Param("accountId") UUID accountId);
}
