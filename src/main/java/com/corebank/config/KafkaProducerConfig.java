package com.corebank.config;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * A single {@code KafkaTemplate<String, String>}, used only by {@code OutboxRelay}. Every event
 * this application publishes now goes through the outbox first (see
 * {@code TransactionEventPublisher}, {@code CustomerEventPublisher}), so there is exactly one
 * thing left that talks to Kafka directly, and it sends pre-serialized JSON strings rather than
 * typed objects -- {@code OutboxEventWriter} already did the serialization when it wrote the row.
 * Before the outbox, this file declared a separate typed {@code ProducerFactory}/
 * {@code KafkaTemplate} pair per event type; both collapsed into this one when the direct-send
 * path they backed was removed.
 *
 * <p>{@code KafkaTemplate.send()} blocks the calling thread for up to {@code max.block.ms}
 * fetching cluster metadata before it will even hand back a future -- the client default is 60
 * seconds, and {@code OutboxRelay} runs this inside a transaction holding row locks on the batch
 * it claimed, so a broker outage blocking that long would hold those locks for a full minute per
 * event. The plain YAML property for this proved unreliable, so it is set explicitly here
 * instead, where there is no ambiguity about whether it actually applied.
 */
@Configuration
public class KafkaProducerConfig {

    private static final int MAX_BLOCK_MS = 3_000;

    @Bean
    public ProducerFactory<String, String> outboxProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties();
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }
}
