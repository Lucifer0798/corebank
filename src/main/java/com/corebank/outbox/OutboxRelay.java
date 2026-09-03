package com.corebank.outbox;

import com.corebank.config.CoreBankProperties;
import com.corebank.outbox.domain.OutboxEvent;
import com.corebank.outbox.repository.OutboxEventRepository;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers durably-written outbox rows to Kafka. The only thing in this application that still
 * talks to Kafka directly -- {@code TransactionEventPublisher} and {@code CustomerEventPublisher}
 * now only write outbox rows (see {@code OutboxEventWriter}), so a broker outage delays delivery
 * rather than losing the event: an unpublished row just sits there until this next runs, however
 * many ticks that takes.
 *
 * <p>Each row's payload is already the exact JSON {@code OutboxEventWriter} serialized, so this
 * sends it as a raw string rather than re-encoding a typed object -- byte-identical to what the
 * old direct-send path produced, and it means a consumer never needs to know an outbox exists.
 *
 * <p>A send failure is logged and left {@code publishedAt IS NULL} for the next tick to retry;
 * it does not fail the whole batch, and it does not stop the scheduler from running again. One
 * genuinely poisoned event (a payload no consumer could ever accept) would retry forever rather
 * than being quarantined -- an accepted limitation for this system's scale, not an oversight; see
 * the {@code attempts}/{@code last_error} columns for the operator-visible trail that would drive
 * a dead-letter cutoff if this ever needed one.
 *
 * <p>{@code corebank.outbox.relay-enabled: false} (the mocked-JWT suite's default, in
 * {@code src/test/resources/application.yml}) turns this off entirely, not just its schedule.
 * That suite's whole design point is needing no live Kafka; without this, every unpublished row
 * a test's transactions and customer changes created would retry every tick against an
 * unreachable broker for the rest of the JVM's life, each attempt blocking up to
 * {@code send-timeout}. It cost one test 22 seconds before this existed, once earlier tests in
 * the same run had left enough of a backlog behind. {@code CoreBankTestcontainersIT} runs against
 * a real broker, so it overrides this back to {@code true}.
 */
@Component
@ConditionalOnProperty(prefix = "corebank.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CoreBankProperties.Outbox properties;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                        CoreBankProperties properties) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties.outbox();
    }

    @Scheduled(fixedDelayString = "${corebank.outbox.relay-interval:2s}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.lockNextBatch(PageRequest.of(0, properties.batchSize()));
        for (OutboxEvent event : batch) {
            deliver(event);
        }
    }

    private void deliver(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished();
            log.debug("Relayed outbox event {} to {}", event.getId(), event.getTopic());
        } catch (ExecutionException | TimeoutException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            event.markFailed(ex.toString());
            log.warn("Could not relay outbox event {} to {} (attempt {}): {}",
                    event.getId(), event.getTopic(), event.getAttempts(), ex.toString());
        }
    }
}
