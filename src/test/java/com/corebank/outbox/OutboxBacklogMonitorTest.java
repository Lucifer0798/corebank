package com.corebank.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.outbox.domain.OutboxAggregateType;
import com.corebank.outbox.domain.OutboxEvent;
import com.corebank.outbox.repository.OutboxBacklog;
import com.corebank.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The outbox's backlog, measured from the table. The relay is off in the test suite, so the shared
 * database holds unsent events from other tests; these tests plant one far older than any of them
 * and judge the clock by it.
 */
@SpringBootTest
class OutboxBacklogMonitorTest {

    /** Older than anything another test writes, which all carry the real time. */
    private static final Instant PLANTED = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired private OutboxEventRepository repository;
    @Autowired private JdbcTemplate jdbc;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private OutboxEvent planted;
    private OutboxEvent later;

    @AfterEach
    void removePlanted() {
        for (OutboxEvent event : new OutboxEvent[] {planted, later}) {
            if (event != null) {
                repository.deleteById(event.getId());
            }
        }
    }

    private OutboxBacklogMonitor monitorAt(Instant now) {
        return new OutboxBacklogMonitor(repository, Clock.fixed(now, ZoneOffset.UTC), meters);
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private void plant() {
        planted = repository.save(OutboxEvent.create(OutboxAggregateType.TRANSACTION, "t", "k", "{}"));
        jdbc.update("update outbox_event set created_at = ? where id = ?", Timestamp.from(PLANTED), planted.getId());
    }

    @Test
    @DisplayName("both gauges exist at zero before anything is measured")
    void theGaugesExistBeforeTheFirstMeasurement() {
        new OutboxBacklogMonitor(mock(OutboxEventRepository.class), Clock.systemUTC(), meters);

        assertThat(gauge(OutboxBacklogMonitor.WAITING)).isZero();
        assertThat(gauge(OutboxBacklogMonitor.OLDEST_AGE)).isZero();
    }

    @Test
    @DisplayName("the oldest unsent event's age is how long it has waited")
    void theOldestEventsAgeIsMeasured() {
        long before = repository.countByPublishedAtIsNull();
        plant();

        OutboxBacklogMonitor monitor = monitorAt(PLANTED.plusSeconds(600));
        monitor.measure();

        assertThat(gauge(OutboxBacklogMonitor.OLDEST_AGE)).isEqualTo(600);
        assertThat(gauge(OutboxBacklogMonitor.WAITING)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("an event that has been sent no longer counts, and one written after now has not waited")
    void aSentEventStopsCounting() {
        plant();
        // Written after the monitor's "now": another replica's clock running ahead, say. That reads
        // as no wait, not a negative one.
        later = repository.save(OutboxEvent.create(OutboxAggregateType.TRANSACTION, "t", "k", "{}"));
        jdbc.update("update outbox_event set created_at = ? where id = ?",
                Timestamp.from(PLANTED.plusSeconds(3600)), later.getId());
        OutboxBacklogMonitor monitor = monitorAt(PLANTED.plusSeconds(600));
        monitor.measure();
        double waitingBefore = gauge(OutboxBacklogMonitor.WAITING);

        jdbc.update("update outbox_event set published_at = ? where id = ?", Timestamp.from(PLANTED), planted.getId());
        monitor.measure();

        assertThat(gauge(OutboxBacklogMonitor.WAITING)).isEqualTo(waitingBefore - 1);
        assertThat(gauge(OutboxBacklogMonitor.OLDEST_AGE)).isZero();
    }

    @Test
    @DisplayName("nothing waiting reads as no wait at all")
    void nothingWaitingIsZero() {
        OutboxEventRepository empty = mock(OutboxEventRepository.class);
        when(empty.backlog()).thenReturn(new OutboxBacklog(0, null));
        OutboxBacklogMonitor monitor = new OutboxBacklogMonitor(empty, Clock.systemUTC(), meters);

        monitor.measure();

        assertThat(gauge(OutboxBacklogMonitor.WAITING)).isZero();
        assertThat(gauge(OutboxBacklogMonitor.OLDEST_AGE)).isZero();
    }
}
