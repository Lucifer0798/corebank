package com.corebank.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
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
        BackOffExecution execution = KafkaConsumerConfig.searchIndexingBackOff().start();

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
    @DisplayName("both search indexers' listeners use it, and notifications' do not")
    @SuppressWarnings("unchecked")
    void onlyTheSearchListenersUseIt() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConsumerFactory<String, TransactionPostedEvent> transactions = mock(ConsumerFactory.class);
        ConsumerFactory<String, CustomerChangedEvent> customers = mock(ConsumerFactory.class);

        assertThat(config.transactionSearchIndexerContainerFactory(transactions).createContainer("t")
                .getCommonErrorHandler()).isInstanceOf(DefaultErrorHandler.class);
        assertThat(config.customerListenerContainerFactory(customers).createContainer("c")
                .getCommonErrorHandler()).isInstanceOf(DefaultErrorHandler.class);
        // Notifications have their own rules for a failure; this change is only for search.
        assertThat(config.transactionListenerContainerFactory(transactions).createContainer("t")
                .getCommonErrorHandler()).isNull();
    }
}
