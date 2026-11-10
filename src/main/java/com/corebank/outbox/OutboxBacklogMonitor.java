package com.corebank.outbox;

import com.corebank.outbox.repository.OutboxBacklog;
import com.corebank.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How far behind the outbox is, as two gauges.
 *
 * <p>The relay retries a failed send forever and logs a WARN each time, which is right for not
 * losing events and useless for noticing that none are getting through. While Kafka is unreachable,
 * notifications, the search index and spending insights all fall silently behind. The oldest
 * waiting event's age is the number that says so: a busy outbox has many events, but a healthy one
 * never has an old one.
 *
 * <p>Measured on its own schedule rather than on every scrape, so Prometheus scraping every five
 * seconds does not mean a query every five seconds. Not tied to the relay's on/off switch: a relay
 * switched off by mistake is one of the things this should catch.
 */
@Component
public class OutboxBacklogMonitor {

    static final String WAITING = "corebank.outbox.waiting";
    static final String OLDEST_AGE = "corebank.outbox.oldest.waiting.age";

    private final OutboxEventRepository repository;
    private final Clock clock;
    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxBacklogMonitor(OutboxEventRepository repository, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.clock = clock;
        // Zero until the first measurement. The alert needs the series to exist, and a restart
        // briefly reporting no backlog is corrected within one interval.
        Gauge.builder(WAITING, waiting, AtomicLong::get)
                .description("Outbox events not yet sent to Kafka")
                .baseUnit("events")
                .register(meterRegistry);
        Gauge.builder(OLDEST_AGE, oldestAgeSeconds, AtomicLong::get)
                .description("How long the oldest outbox event not yet sent to Kafka has waited, 0 when none wait")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${corebank.outbox.backlog-check-interval:30s}")
    public void measure() {
        OutboxBacklog backlog = repository.backlog();
        waiting.set(backlog.waiting());
        oldestAgeSeconds.set(backlog.oldest() == null
                ? 0
                : Math.max(0, Duration.between(backlog.oldest(), clock.instant()).toSeconds()));
    }
}
