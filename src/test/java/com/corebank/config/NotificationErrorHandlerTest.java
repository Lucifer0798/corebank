package com.corebank.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.corebank.common.metrics.BackgroundFailures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * What the notification listener does with each kind of failure, through the real error handler.
 *
 * <p>"Waited out" shows as the handler seeking the consumer back to the failed record, so the next
 * poll delivers it again, and telling the container so with a {@code RecordInRetryException}.
 * "Skipped" shows as the record being recovered instead: counted, no seek, and nothing thrown. The pause schedule itself, and that it never gives up, is {@link SearchIndexingRetryTest}'s.
 */
class NotificationErrorHandlerTest {

    private static final TopicPartition PARTITION = new TopicPartition("corebank.transactions.posted", 1);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DefaultErrorHandler handler = KafkaConsumerConfig.notificationErrorHandler(meters);
    @SuppressWarnings("unchecked")
    private final Consumer<String, String> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    /** Hands the handler a listener's failure, the way the container does; true if it will retry. */
    private boolean fail(Exception cause) {
        try {
            handler.handleRemaining(new ListenerExecutionFailedException("listener failed", cause),
                    List.of(new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(), 42L, "TXN-1", "{}")),
                    consumer, container);
            return false;
        } catch (RuntimeException thrown) {
            // Package-private in Spring Kafka, so recognised by name.
            assertThat(thrown.getClass().getSimpleName()).isEqualTo("RecordInRetryException");
            return true;
        }
    }

    private double counted(String task) {
        return meters.get(BackgroundFailures.METRIC).tag("task", task).counter().count();
    }

    private void assertWaitedOut(boolean retrying) {
        assertThat(retrying).as("handed back to be delivered again").isTrue();
        verify(consumer).seek(PARTITION, 42L);
        assertThat(counted(KafkaConsumerConfig.NOTIFICATIONS)).isZero();
        assertThat(counted(KafkaConsumerConfig.MALFORMED)).isZero();
    }

    @Test
    @DisplayName("both counters exist at zero before anything has failed")
    void countersStartAtZero() {
        assertThat(counted(KafkaConsumerConfig.NOTIFICATIONS)).isZero();
        assertThat(counted(KafkaConsumerConfig.MALFORMED)).isZero();
    }

    @Test
    @DisplayName("no connection to be had: waited out")
    void noConnectionIsWaitedOut() {
        boolean retrying = fail(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out")));

        assertWaitedOut(retrying);
    }

    @Test
    @DisplayName("a transient database error, such as a statement timing out: waited out")
    void aTransientErrorIsWaitedOut() {
        boolean retrying = fail(new QueryTimeoutException("statement timed out"));

        assertWaitedOut(retrying);
    }

    @Test
    @DisplayName("a bug that will fail every time: skipped at once and counted, not repeated for ever")
    void aBugIsSkipped() {
        boolean retrying = fail(new DataIntegrityViolationException("value too long for type character varying(36)"));

        assertThat(retrying).isFalse();
        verify(consumer, never()).seek(any(), anyLong());
        assertThat(counted(KafkaConsumerConfig.NOTIFICATIONS)).isEqualTo(1);
        assertThat(counted(KafkaConsumerConfig.MALFORMED)).isZero();
    }

    @Test
    @DisplayName("a message that could not be read: skipped at once and counted as malformed")
    void aMalformedMessageIsSkipped() {
        boolean retrying = fail(new DeserializationException("failed to deserialize", "not json {".getBytes(), false,
                new IllegalArgumentException("Unexpected character")));

        assertThat(retrying).isFalse();
        verify(consumer, never()).seek(any(), anyLong());
        assertThat(counted(KafkaConsumerConfig.MALFORMED)).isEqualTo(1);
        assertThat(counted(KafkaConsumerConfig.NOTIFICATIONS)).isZero();
    }
}
