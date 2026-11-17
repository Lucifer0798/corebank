package com.corebank.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.corebank.common.metrics.BackgroundFailures;
import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOffExecution;

/**
 * The search indexers' listeners wait out an OpenSearch outage instead of giving up on it.
 *
 * <p>The end-to-end proof is {@code CoreBankTestcontainersIT}, which pauses OpenSearch and watches
 * search catch up. It cannot tell this schedule from Spring Kafka's default, which also retries,
 * ten times, so an outage shorter than about 50 seconds passes either way. Giving up is what this
 * pins down.
 */
class SearchIndexingRetryTest {

    @Test
    @DisplayName("the pause starts at a second, doubles, stops growing at a minute, and never gives up")
    void theScheduleNeverGivesUp() {
        BackOffExecution execution = KafkaConsumerConfig.backOffUntilItWorks().start();

        List<Long> pauses = new ArrayList<>();
        // A week of one-minute pauses: far past anything that stops.
        for (int attempt = 0; attempt < 10_080; attempt++) {
            pauses.add(execution.nextBackOff());
        }

        assertThat(pauses.getFirst()).isEqualTo(1_000);
        assertThat(pauses.subList(0, 7)).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L);
        assertThat(pauses).doesNotContain(BackOffExecution.STOP).allMatch(pause -> pause <= 60_000);
        assertThat(pauses.getLast()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("both search indexers' listeners use it, and notifications' have their own")
    @SuppressWarnings("unchecked")
    void eachListenerHasItsHandler() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConsumerFactory<String, TransactionPostedEvent> transactions = mock(ConsumerFactory.class);
        ConsumerFactory<String, CustomerChangedEvent> customers = mock(ConsumerFactory.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        assertThat(config.transactionSearchIndexerContainerFactory(transactions).createContainer("t")
                .getCommonErrorHandler()).isInstanceOf(DefaultErrorHandler.class);
        assertThat(config.customerListenerContainerFactory(customers).createContainer("c")
                .getCommonErrorHandler()).isInstanceOf(DefaultErrorHandler.class);

        // The notifications one skips and counts what will not clear, where search's retries
        // everything: tell them apart by what each does with a bug.
        var notifications = config.transactionListenerContainerFactory(transactions, meters).createContainer("t");
        DefaultErrorHandler handler = (DefaultErrorHandler) notifications.getCommonErrorHandler();
        Consumer<?, ?> consumer = mock(Consumer.class);
        handler.handleRemaining(new ListenerExecutionFailedException("bug", new IllegalStateException("a bug")),
                List.of(new ConsumerRecord<>("corebank.transactions.posted", 0, 7L, "k", "v")), consumer, notifications);
        assertThat(meters.get(BackgroundFailures.METRIC).tag("task", KafkaConsumerConfig.NOTIFICATIONS).counter().count())
                .isEqualTo(1);
    }
}
