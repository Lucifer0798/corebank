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

    /**
     * The REST client behind {@code OpenSearchClient} otherwise inherits Apache HttpClient's own
     * defaults: no request-level timeout at all, and a connection pool sized for a handful of
     * concurrent callers (10 per route) rather than for however many requests virtual threads let
     * reach {@code SearchService} at once. A slow OpenSearch node would hang those threads
     * indefinitely instead of failing into the {@code SearchUnavailableException} path that
     * already exists for exactly this.
     */
    public record Search(
            @NotBlank String opensearchUri,
            @NotNull Duration connectTimeout,
            @NotNull Duration socketTimeout,
            @Positive int maxConnections) {
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
