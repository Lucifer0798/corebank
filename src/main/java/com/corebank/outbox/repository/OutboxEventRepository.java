package com.corebank.outbox.repository;

import com.corebank.outbox.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The rows {@link com.corebank.outbox.OutboxRelay} works on next, oldest first, row-locked
     * for the caller's transaction. {@code SKIP LOCKED} (Hibernate's {@code -2} lock-timeout
     * magic value) is what makes this safe if this application is ever scaled to more than one
     * replica: two relays polling at once claim disjoint batches instead of blocking on, or
     * double-sending, each other's rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC")
    List<OutboxEvent> lockNextBatch(Pageable pageable);

    long countByPublishedAtIsNull();

    /** One statement, so the count and the oldest agree. {@code oldest} is null when none wait. */
    @Query("SELECT new com.corebank.outbox.repository.OutboxBacklog(COUNT(e), MIN(e.createdAt)) "
            + "FROM OutboxEvent e WHERE e.publishedAt IS NULL")
    OutboxBacklog backlog();
}
