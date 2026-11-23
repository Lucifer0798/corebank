package com.corebank.retention;

import com.corebank.common.metrics.BackgroundFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link RetentionService} every night, after the ledger check. Switched off in the plain test
 * suite, like the other runners: it would delete rows other tests are about to look at.
 */
@Component
@ConditionalOnProperty(prefix = "corebank.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RetentionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);

    static final String TASK = "retention";

    private final RetentionService service;
    private final Counter failures;

    public RetentionRunner(RetentionService service, MeterRegistry meterRegistry) {
        this.service = service;
        this.failures = BackgroundFailures.counter(meterRegistry, TASK);
    }

    @Scheduled(cron = "${corebank.retention.cron:0 15 3 * * *}", zone = "UTC")
    public void run() {
        try {
            RetentionService.Pruned pruned = service.prune();
            log.info("Retention deleted {} published outbox event(s) and {} expired idempotency record(s)",
                    pruned.outboxEvents(), pruned.idempotencyRecords());
        } catch (RuntimeException ex) {
            // Left for the next night, which deletes everything this one would have and more.
            failures.increment();
            log.error("Retention could not run", ex);
        }
    }
}
