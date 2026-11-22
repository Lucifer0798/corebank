package com.corebank.idempotency;

import com.corebank.common.exception.ConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes money-moving POSTs safe to retry.
 *
 * <p>A caller sends an {@code Idempotency-Key}. The first request claims that key by inserting
 * a row in its own committed transaction; the unique constraint on (scope, key) means a
 * concurrent duplicate loses the insert and reads the winner's outcome instead of posting again.
 * When the operation succeeds its response is stored and replayed verbatim; when it fails the
 * claim is released so the client can genuinely retry.
 *
 * <p><b>The operation and its completion commit together.</b> They used to be two transactions:
 * the operation committed, then a second transaction marked the claim done. A crash or a database
 * blip between them left the claim IN_PROGRESS for good, with nothing to expire it, so every retry
 * with that key answered 409 {@code REQUEST_IN_PROGRESS} -- including a standing order's, whose key
 * is derived, which then recorded a payment that had gone out as a failed one. Now the posting, its
 * outbox event and the claim's completion are one transaction, so a claim left IN_PROGRESS has
 * changed nothing.
 *
 * <p><b>So an abandoned claim can be taken over.</b> A claim still IN_PROGRESS after
 * {@link #ABANDONED_AFTER} is handed to the next identical request under a new token. Each claim
 * carries a token, and completion only succeeds while the token is the caller's own, so if the
 * original request was merely slow rather than gone, its completion finds the token changed and
 * its whole operation rolls back: only one of them ever commits. Claims made before tokens existed
 * have none and are never taken over.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Far longer than any request takes; a claim older than this was abandoned. */
    static final Duration ABANDONED_AFTER = Duration.ofMinutes(1);

    static final String TAKEN_OVER = "corebank.idempotency.claims.taken.over";

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Counter takenOver;

    public IdempotencyService(IdempotencyRecordRepository records,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager,
                              MeterRegistry meterRegistry,
                              Clock clock) {
        this.records = records;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.takenOver = Counter.builder(TAKEN_OVER)
                .description("Abandoned idempotency claims handed to a retry")
                .register(meterRegistry);
    }

    public <T> Result<T> execute(String scope, String key, Object request, Class<T> responseType,
                                 Supplier<T> operation) {
        String requestHash = requestHash(request);
        UUID token = UUID.randomUUID();

        Result<T> replay = claim(scope, key, requestHash, responseType, token);
        if (replay != null) {
            return replay;
        }

        T response;
        try {
            // One transaction for the operation and the claim's completion. The operation's own
            // @Transactional joins it, so the posting, its outbox row and the completed claim commit
            // together or not at all.
            response = requiresNew.execute(status -> {
                T value = operation.get();
                String body = objectMapper.writeValueAsString(value);
                if (records.complete(scope, key, token, 201, body, Instant.now(clock)) != 1) {
                    throw new ConflictException("IDEMPOTENCY_CLAIM_LOST",
                            "This request took too long and a retry with the same Idempotency-Key took it over; "
                                    + "retry with the same key to get the outcome");
                }
                return value;
            });
        } catch (RuntimeException ex) {
            release(scope, key, token);
            throw ex;
        }
        return new Result<>(response, false);
    }

    /**
     * Returns a replayed result when the key has already been used, or null when this caller
     * holds the claim and should go on to perform the operation.
     */
    private <T> Result<T> claim(String scope, String key, String requestHash, Class<T> responseType, UUID token) {
        try {
            requiresNew.executeWithoutResult(status -> {
                IdempotencyRecord record = new IdempotencyRecord();
                record.setScope(scope);
                record.setIdempotencyKey(key);
                record.setRequestHash(requestHash);
                record.setStatus(IdempotencyStatus.IN_PROGRESS);
                record.setCreatedAt(Instant.now(clock));
                record.setClaimToken(token);
                records.saveAndFlush(record);
            });
            return null;
        } catch (DataIntegrityViolationException ex) {
            return existingResult(scope, key, requestHash, responseType, token);
        }
    }

    private <T> Result<T> existingResult(String scope, String key, String requestHash, Class<T> responseType,
                                         UUID token) {
        IdempotencyRecord existing = requiresNew.execute(status ->
                records.findByScopeAndIdempotencyKey(scope, key).orElse(null));

        if (existing == null) {
            // The winning claim was rolled back between our failed insert and this read.
            throw new ConflictException("IDEMPOTENCY_RACE",
                    "The request could not be claimed; retry with the same Idempotency-Key");
        }
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key '" + key + "' was already used with a different request body");
        }
        if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            if (abandoned(existing) && takeOver(existing, token)) {
                return null;
            }
            throw new ConflictException("REQUEST_IN_PROGRESS",
                    "An identical request is still being processed; retry shortly");
        }

        log.debug("Replaying idempotent response for {}/{}", scope, key);
        meterRegistry.counter("corebank.idempotency.replayed", "scope", scope).increment();
        return new Result<>(objectMapper.readValue(existing.getResponseBody(), responseType), true);
    }

    private boolean abandoned(IdempotencyRecord claim) {
        // A claim with no token predates this rule and may have posted: never taken over.
        return claim.getClaimToken() != null
                && claim.getCreatedAt().isBefore(Instant.now(clock).minus(ABANDONED_AFTER));
    }

    private boolean takeOver(IdempotencyRecord claim, UUID token) {
        Integer updated = requiresNew.execute(status ->
                records.takeOver(claim.getId(), claim.getClaimToken(), token, Instant.now(clock)));
        if (updated == null || updated != 1) {
            // Another retry took it first, or the original finished in the meantime.
            return false;
        }
        takenOver.increment();
        log.warn("Took over the abandoned claim on {}/{}, made {}; the request that made it never finished",
                claim.getScope(), claim.getIdempotencyKey(), claim.getCreatedAt());
        return true;
    }

    /** Frees the key after a failure so the client can retry the same request, if it is still ours. */
    private void release(String scope, String key, UUID token) {
        try {
            requiresNew.executeWithoutResult(status -> records.release(scope, key, token));
        } catch (RuntimeException ex) {
            // Left IN_PROGRESS, it is taken over once abandoned, so the key is not lost for good.
            log.warn("Could not release idempotency claim {}/{}", scope, key, ex);
        }
    }

    /** What a claim stores to tell a retry from a different request under the same key. */
    public String requestHash(Object request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }

    /** The operation's result, plus whether it came from a replayed key rather than fresh work. */
    public record Result<T>(T value, boolean replayed) {
    }
}
