package com.corebank.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(String scope, String idempotencyKey);

    /**
     * Hands an abandoned claim to a new request, only if it is still the claim that was judged
     * abandoned. Two retries racing to take it over: the row lock serialises them, and the second
     * finds the token already changed. 1 if this caller now holds it.
     */
    @Modifying
    @Query("""
            UPDATE IdempotencyRecord r SET r.claimToken = :next, r.createdAt = :now
             WHERE r.id = :id
               AND r.status = com.corebank.idempotency.IdempotencyStatus.IN_PROGRESS
               AND r.claimToken = :current
            """)
    int takeOver(@Param("id") UUID id, @Param("current") UUID current, @Param("next") UUID next,
                 @Param("now") Instant now);

    /**
     * Marks the claim done with the operation's response, in the operation's own transaction, only
     * if the caller still holds it. 0 means a retry took it over, and the caller must roll back.
     */
    @Modifying
    @Query("""
            UPDATE IdempotencyRecord r
               SET r.status = com.corebank.idempotency.IdempotencyStatus.COMPLETED,
                   r.responseStatus = :responseStatus, r.responseBody = :responseBody, r.completedAt = :now
             WHERE r.scope = :scope AND r.idempotencyKey = :key AND r.claimToken = :token
               AND r.status = com.corebank.idempotency.IdempotencyStatus.IN_PROGRESS
            """)
    int complete(@Param("scope") String scope, @Param("key") String key, @Param("token") UUID token,
                 @Param("responseStatus") int responseStatus, @Param("responseBody") String responseBody,
                 @Param("now") Instant now);

    /** Frees the key after a failure, only if the caller still holds the claim. */
    @Modifying
    @Query("""
            DELETE FROM IdempotencyRecord r
             WHERE r.scope = :scope AND r.idempotencyKey = :key AND r.claimToken = :token
               AND r.status = com.corebank.idempotency.IdempotencyStatus.IN_PROGRESS
            """)
    int release(@Param("scope") String scope, @Param("key") String key, @Param("token") UUID token);
}
