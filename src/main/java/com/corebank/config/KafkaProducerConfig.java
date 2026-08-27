package com.corebank.config;

import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * {@code KafkaTemplate.send()} blocks the calling thread -- here, the HTTP request thread, via
 * {@code TransactionEventPublisher}'s after-commit listener -- for up to {@code max.block.ms}
 * fetching cluster metadata before it will even hand back a future to attach a callback to. The
 * client default is 60 seconds. Publishing is fire-and-forget by design, so a broker outage
 * should fail fast rather than hold requests hostage; the plain YAML property for this proved
 * unreliable, so it is set explicitly here instead, where there is no ambiguity about whether it
 * actually applied.
 */
@Configuration
public class KafkaProducerConfig {

    private static final int MAX_BLOCK_MS = 3_000;

    @Bean
    public ProducerFactory<String, TransactionPostedEvent> transactionEventProducerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties();
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, TransactionPostedEvent> transactionEventKafkaTemplate(
            ProducerFactory<String, TransactionPostedEvent> transactionEventProducerFactory) {
        return new KafkaTemplate<>(transactionEventProducerFactory);
    }

    @Bean
    public ProducerFactory<String, CustomerChangedEvent> customerEventProducerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties();
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, CustomerChangedEvent> customerEventKafkaTemplate(
            ProducerFactory<String, CustomerChangedEvent> customerEventProducerFactory) {
        return new KafkaTemplate<>(customerEventProducerFactory);
    }
}
