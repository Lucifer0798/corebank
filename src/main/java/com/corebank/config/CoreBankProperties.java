package com.corebank.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Everything the platform needs configured per environment, validated at startup. */
@Validated
@ConfigurationProperties(prefix = "corebank")
public record CoreBankProperties(
        @Valid @NotNull Ledger ledger,
        @Valid @NotNull AccountSettings account,
        @Valid @NotNull Web web,
        @Valid @NotNull Search search,
        @Valid @NotNull Outbox outbox) {

    public record Ledger(
            @NotBlank String cashAccountNumber,
            @NotBlank String suspenseAccountNumber) {
    }

    public record AccountSettings(@NotBlank String numberPrefix) {
    }

    /** Origins the browser-facing frontend is served from, for CORS. */
    public record Web(@NotEmpty List<String> allowedOrigins) {
    }

    public record Search(@NotBlank String opensearchUri) {
    }

    /**
     * {@code sendTimeout} bounds how long {@code OutboxRelay} blocks on a single Kafka send
     * before giving up and retrying it next tick -- the row-locking transaction it runs in
     * (see {@code OutboxEventRepository.lockNextBatch}) holds those locks for the duration, so
     * an unbounded wait here would hold a batch of rows locked indefinitely during a broker
     * outage instead of just leaving them durably unpublished.
     */
    public record Outbox(@Positive int batchSize, @NotNull Duration sendTimeout) {
    }
}
