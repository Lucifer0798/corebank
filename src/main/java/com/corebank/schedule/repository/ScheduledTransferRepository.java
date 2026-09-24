package com.corebank.schedule.repository;

import com.corebank.schedule.domain.ScheduleStatus;
import com.corebank.schedule.domain.ScheduledTransfer;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
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

public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer, UUID> {

    /**
     * Ids of the mandates that have come due, oldest first. Ids rather than entities, and no lock:
     * this is only the runner deciding what to look at. Each one is then re-read and locked in its
     * own transaction, because a batch that shared one transaction would roll back every
     * occurrence in it when any single transfer was refused.
     */
    @Query("""
            SELECT s.id FROM ScheduledTransfer s
            WHERE s.status = :status AND s.nextRunOn <= :on
            ORDER BY s.nextRunOn ASC, s.id ASC
            """)
    List<UUID> findDueIds(@Param("status") ScheduleStatus status, @Param("on") LocalDate on, Pageable pageable);

    /**
     * Claims one mandate for this instance, or returns empty when another already holds it.
     *
     * <p>{@code SKIP LOCKED} (Hibernate's {@code -2} lock-timeout magic value, the same trick
     * {@code OutboxEventRepository.lockNextBatch} uses) is what makes the runner safe on more than
     * one replica. Without it two instances polling the same second would queue on the row and
     * both run the occurrence -- the second harmlessly, thanks to the derived idempotency key, but
     * only after blocking for the length of the first one's transfer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT s FROM ScheduledTransfer s WHERE s.id = :id")
    Optional<ScheduledTransfer> lockById(@Param("id") UUID id);

    @Query("""
            SELECT s FROM ScheduledTransfer s
            WHERE s.sourceAccountId = :accountId OR s.destinationAccountId = :accountId
            """)
    Page<ScheduledTransfer> findForAccount(@Param("accountId") UUID accountId, Pageable pageable);
}
