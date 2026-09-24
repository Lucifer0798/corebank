package com.corebank.schedule;

import com.corebank.schedule.service.ScheduledTransferService;
import com.corebank.schedule.service.ScheduledTransferService.DueOccurrence;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns due mandates into postings. The only thing in the application that moves money without a
 * request behind it.
 *
 * <p>One occurrence is advanced per mandate per tick, deliberately. After an outage a daily
 * standing order may be several occurrences behind; it catches up over successive ticks rather
 * than posting the backlog in one burst, which keeps each posting a separate, individually
 * idempotent unit and keeps a long backlog from monopolising a tick.
 *
 * <p>Nothing here is transactional. Each step the service performs manages its own transaction,
 * and the gaps between them are safe because every attempt at a given occurrence derives the same
 * idempotency key -- see {@link ScheduledTransferService} for the full argument.
 *
 * <p>Switched off in the plain test suite for the same reason the outbox relay is: a background
 * poller that posts money is not something every unrelated test should have running underneath it.
 */
@Component
@ConditionalOnProperty(prefix = "corebank.scheduled-transfers", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ScheduledTransferRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTransferRunner.class);

    private final ScheduledTransferService service;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ScheduledTransferRunner(ScheduledTransferService service, MeterRegistry meterRegistry, Clock clock) {
        this.service = service;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${corebank.scheduled-transfers.poll-interval:60s}")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        for (UUID id : service.findDue(today)) {
            runOne(id, today);
        }
    }

    private void runOne(UUID id, LocalDate today) {
        Optional<DueOccurrence> claimed = service.claim(id, today);
        if (claimed.isEmpty()) {
            // Another replica holds the row, or it stopped being due between the poll and now.
            return;
        }
        DueOccurrence occurrence = claimed.get();
        try {
            service.execute(occurrence);
            service.markSuccess(id, occurrence.dueOn());
            count("posted");
            log.info("Scheduled transfer {} posted its {} occurrence", id, occurrence.dueOn());
        } catch (RuntimeException ex) {
            // Insufficient funds is the expected case and is not an application fault, so this
            // logs at WARN with the reason rather than dumping a stack trace every time an
            // account happens to be short on the first of the month.
            service.markFailure(id, occurrence.dueOn(), ex.getMessage());
            count("failed");
            log.warn("Scheduled transfer {} could not post its {} occurrence: {}",
                    id, occurrence.dueOn(), ex.toString());
        }
    }

    private void count(String outcome) {
        meterRegistry.counter("corebank.scheduled.transfers", "outcome", outcome).increment();
    }
}
