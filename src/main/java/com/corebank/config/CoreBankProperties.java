package com.corebank.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
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
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull ScheduledTransfers scheduledTransfers,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Interest interest) {

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

    /**
     * {@code maxConsecutiveFailures} is where a standing instruction stops being retried and
     * starts being somebody's problem. Too low and one short morning kills a year-long mandate;
     * too high and a closed destination account is retried daily forever, with nobody told. Three
     * is the smallest number that survives an ordinary run of bad luck without hiding a mandate
     * that genuinely cannot be honoured.
     *
     * <p>{@code batchSize} bounds one tick, so a large backlog is worked through over several
     * ticks rather than in one long transaction-heavy sweep.
     */
    public record ScheduledTransfers(
            @Positive int batchSize,
            @Positive int maxConsecutiveFailures) {
    }

    /**
     * Velocity controls: how much may leave one account, and how fast.
     *
     * <p>Both are per <em>account</em> rather than per customer, which is the narrower and more
     * conservative reading -- a customer holding several accounts gets the limit on each. Tying it
     * to the customer would be the stricter control and is a deliberate non-goal here: it would
     * mean every posting locking or summing across an unbounded set of sibling accounts.
     *
     * <p>{@code dailyDebitLimit} counts a UTC calendar day. Not a rolling 24 hours, which sounds
     * fairer and is worse: a rolling window means a customer refused at 23:00 cannot be told when
     * they may try again without the bank replaying their own history at them.
     */
    public record Limits(
            @NotNull @Positive BigDecimal dailyDebitLimit,
            @NotNull @Positive BigDecimal singleTransactionLimit) {
    }

    /**
     * Interest on savings balances.
     *
     * <p>{@code dayCountBasis} is the denominator a daily rate is derived from, and it is a policy
     * choice rather than a fact: 365 ignores leap years and slightly under-pays in one year out of
     * four, 360 is the old money-market convention and over-pays every year. It is configurable
     * because the right answer depends on the product, and named rather than hardcoded so that
     * whoever changes it can see what they are changing.
     */
    public record Interest(
            @NotNull BigDecimal savingsAnnualRate,
            @Positive int dayCountBasis) {
    }
}
