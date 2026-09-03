package com.corebank.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.config.CoreBankProperties;
import com.corebank.outbox.domain.OutboxAggregateType;
import com.corebank.outbox.domain.OutboxEvent;
import com.corebank.outbox.repository.OutboxEventRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * The delivery logic in isolation: given a batch the repository hands back, does a successful
 * send mark a row published, does a failed one leave it retryable, and does one bad row in a
 * batch still let the rest through. Whether Kafka is actually reachable, and whether an outage
 * and recovery genuinely survives, is CoreBankTestcontainersIT's job -- a mock proves this
 * method's own branching, not that a real broker round-trip works.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        CoreBankProperties properties = new CoreBankProperties(
                null, null, null, null,
                new CoreBankProperties.Outbox(50, Duration.ofSeconds(3)));
        relay = new OutboxRelay(repository, kafkaTemplate, properties);
    }

    private static OutboxEvent event(String topic, String key) {
        return OutboxEvent.create(OutboxAggregateType.TRANSACTION, topic, key, "{}");
    }

    @Test
    @DisplayName("a successful send marks the row published and clears any earlier error")
    void successfulSendMarksPublished() {
        OutboxEvent posted = event("corebank.transactions.posted", "TXN-1");
        posted.markFailed("a transient failure from an earlier tick");
        when(repository.lockNextBatch(any())).thenReturn(List.of(posted));
        when(kafkaTemplate.send(eq("corebank.transactions.posted"), eq("TXN-1"), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        relay.relay();

        assertThat(posted.getPublishedAt()).isNotNull();
        assertThat(posted.getLastError()).isNull();
    }

    @Test
    @DisplayName("a failed send leaves the row unpublished and records the attempt")
    void failedSendLeavesRowRetryable() {
        OutboxEvent changed = event("corebank.customers.changed", "cust-1");
        when(repository.lockNextBatch(any())).thenReturn(List.of(changed));
        when(kafkaTemplate.send(eq("corebank.customers.changed"), eq("cust-1"), eq("{}")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unreachable")));

        relay.relay();

        assertThat(changed.getPublishedAt()).isNull();
        assertThat(changed.getAttempts()).isEqualTo(1);
        assertThat(changed.getLastError()).contains("broker unreachable");
    }

    @Test
    @DisplayName("repeated failures keep incrementing attempts rather than resetting")
    void repeatedFailuresAccumulateAttempts() {
        OutboxEvent event = event("t", "k");
        when(repository.lockNextBatch(any())).thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("t"), eq("k"), eq("{}")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("still down")));

        relay.relay();
        relay.relay();
        relay.relay();

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("one failing row in a batch does not stop the rest from being delivered")
    void oneFailureDoesNotBlockTheRestOfTheBatch() {
        OutboxEvent bad = event("t1", "k1");
        OutboxEvent good = event("t2", "k2");
        when(repository.lockNextBatch(any())).thenReturn(List.of(bad, good));
        when(kafkaTemplate.send(eq("t1"), eq("k1"), eq("{}")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));
        when(kafkaTemplate.send(eq("t2"), eq("k2"), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        relay.relay();

        assertThat(bad.getPublishedAt()).isNull();
        assertThat(good.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("an empty batch is a no-op, not an error")
    void emptyBatchDoesNothing() {
        when(repository.lockNextBatch(any())).thenReturn(List.of());
        relay.relay();
        // No exception, and nothing to assert on the (empty) batch -- the point is this doesn't throw.
    }
}
