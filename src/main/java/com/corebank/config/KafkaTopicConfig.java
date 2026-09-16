package com.corebank.config;

import com.corebank.customer.messaging.CustomerEventPublisher;
import com.corebank.transaction.messaging.TransactionEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Both topics were left to Kafka's own auto-create default (this broker's {@code num.partitions}
 * is 1), which meant every consumer of either one -- {@code TransactionEventLogger},
 * {@code TransactionSearchIndexer}, {@code CustomerSearchIndexer} -- had exactly one partition to
 * share, so raising a listener's concurrency past 1 (see {@code KafkaConsumerConfig}) was a pure
 * no-op: Kafka can only assign a partition to one consumer per group at a time.
 *
 * <p>Declaring the partition count here means the auto-configured {@code KafkaAdmin} reconciles
 * it at startup instead: creating the topic with this shape if it doesn't exist yet, or raising
 * an existing topic's partition count to match if it was created earlier with fewer. Kafka has no
 * way to lower a partition count, only raise it, so this is a one-way move -- the same reason the
 * number below is picked deliberately rather than oversized "to be safe".
 *
 * <p>3 is a reasoned default for a single-broker deployment with no load-test data behind it:
 * enough to make each listener's matching {@code setConcurrency(3)} do something real, not a
 * number sized to any measured throughput requirement.
 */
@Configuration
public class KafkaTopicConfig {

    /** Also used by {@code KafkaConsumerConfig} to size each listener's concurrency to match --
     *  concurrency past the partition count just leaves threads permanently idle. */
    public static final int PARTITIONS = 3;
    private static final int REPLICATION_FACTOR = 1;

    @Bean
    public NewTopic transactionsPostedTopic() {
        return TopicBuilder.name(TransactionEventPublisher.TOPIC)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }

    @Bean
    public NewTopic customersChangedTopic() {
        return TopicBuilder.name(CustomerEventPublisher.TOPIC)
                .partitions(PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }
}
