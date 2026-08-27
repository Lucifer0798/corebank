package com.corebank.config;

import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * A shared, single {@code spring.json.value.default.type} broke the moment a second event type
 * needed its own topic: it forces every listener using the default consumer config onto one
 * fixed payload type, so whichever topic's type wasn't the default would fail to deserialize.
 * Each event type gets its own explicitly-typed {@link ConcurrentKafkaListenerContainerFactory}
 * here instead, referenced by name from {@code @KafkaListener(containerFactory = "...")} -- the
 * same "bind the concrete type, not a shared generic one" fix already applied to the Redis cache
 * serializer in {@code CacheConfig}.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, TransactionPostedEvent> transactionConsumerFactory(
            KafkaProperties kafkaProperties) {
        JsonDeserializer<TransactionPostedEvent> deserializer = new JsonDeserializer<>(TransactionPostedEvent.class);
        deserializer.addTrustedPackages("com.corebank.transaction.messaging");
        return new DefaultKafkaConsumerFactory<>(
                consumerProperties(kafkaProperties), new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionPostedEvent> transactionListenerContainerFactory(
            ConsumerFactory<String, TransactionPostedEvent> transactionConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionPostedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionConsumerFactory);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, CustomerChangedEvent> customerConsumerFactory(KafkaProperties kafkaProperties) {
        JsonDeserializer<CustomerChangedEvent> deserializer = new JsonDeserializer<>(CustomerChangedEvent.class);
        deserializer.addTrustedPackages("com.corebank.customer.messaging");
        return new DefaultKafkaConsumerFactory<>(
                consumerProperties(kafkaProperties), new StringDeserializer(), deserializer);
    }

    /**
     * {@code kafkaProperties.buildConsumerProperties()} still carries
     * {@code spring.kafka.consumer.value-deserializer} as a class-name string, since that YAML
     * property is also (harmlessly) read elsewhere. Supplying both that string *and* a
     * deserializer instance to {@link DefaultKafkaConsumerFactory} is a combination Spring Kafka
     * refuses outright: {@code IllegalStateException: JsonDeserializer must be configured with
     * property setters, or via configuration properties; not both} -- confirmed against a real
     * container startup, not caught by the mocked-JWT test suite, which never starts a real
     * listener container. Each per-type factory here supplies its own deserializer instance, so
     * the class-name property has to come out of the map first.
     */
    private static Map<String, Object> consumerProperties(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildConsumerProperties();
        properties.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        properties.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        return properties;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CustomerChangedEvent> customerListenerContainerFactory(
            ConsumerFactory<String, CustomerChangedEvent> customerConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, CustomerChangedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(customerConsumerFactory);
        return factory;
    }
}
