package com.corebank.idempotency;

import com.corebank.common.exception.ConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyRecordRepository records,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.records = records;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> Result<T> execute(String scope, String key, Object request, Class<T> responseType,
                                 Supplier<T> operation) {
        String requestHash = hash(request);

        Result<T> replay = claim(scope, key, requestHash, responseType);
        if (replay != null) {
            return replay;
        }

        T response;
        try {
            response = operation.get();
        } catch (RuntimeException ex) {
            release(scope, key);
            throw ex;
        }

        complete(scope, key, response);
        return new Result<>(response, false);
    }

    /**
     * Returns a replayed result when the key has already been used, or null when this caller
     * won the claim and should go on to perform the operation.
     */
    private <T> Result<T> claim(String scope, String key, String requestHash, Class<T> responseType) {
        try {
            requiresNew.executeWithoutResult(status -> {
                IdempotencyRecord record = new IdempotencyRecord();
                record.setScope(scope);
                record.setIdempotencyKey(key);
                record.setRequestHash(requestHash);
                record.setStatus(IdempotencyStatus.IN_PROGRESS);
                record.setCreatedAt(Instant.now());
                records.saveAndFlush(record);
            });
            return null;
        } catch (DataIntegrityViolationException ex) {
            return existingResult(scope, key, requestHash, responseType);
        }
    }

    private <T> Result<T> existingResult(String scope, String key, String requestHash, Class<T> responseType) {
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
            throw new ConflictException("REQUEST_IN_PROGRESS",
                    "An identical request is still being processed; retry shortly");
        }

        log.debug("Replaying idempotent response for {}/{}", scope, key);
        return new Result<>(objectMapper.readValue(existing.getResponseBody(), responseType), true);
    }

    private void complete(String scope, String key, Object response) {
        String body = objectMapper.writeValueAsString(response);
        requiresNew.executeWithoutResult(status ->
                records.findByScopeAndIdempotencyKey(scope, key).ifPresent(record -> {
                    record.setStatus(IdempotencyStatus.COMPLETED);
                    record.setResponseStatus(201);
                    record.setResponseBody(body);
                    record.setCompletedAt(Instant.now());
                    records.save(record);
                }));
    }

    /** Frees the key after a failure so the client can retry the same request. */
    private void release(String scope, String key) {
        try {
            requiresNew.executeWithoutResult(status ->
                    records.findByScopeAndIdempotencyKey(scope, key).ifPresent(records::delete));
        } catch (RuntimeException ex) {
            log.warn("Could not release idempotency claim {}/{}", scope, key, ex);
        }
    }

    private String hash(Object request) {
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
