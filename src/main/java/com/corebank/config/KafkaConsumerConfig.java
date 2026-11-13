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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

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
        // Matches KafkaTopicConfig.PARTITIONS: one consumer thread per partition, so this
        // listener's own group can actually consume in parallel rather than leaving idle threads.
        factory.setConcurrency(KafkaTopicConfig.PARTITIONS);
        return factory;
    }

    /**
     * A second factory over the same {@link ConsumerFactory}, batch-mode this time, so
     * {@code TransactionSearchIndexer} can take a whole poll's worth of events as one
     * {@code List<TransactionPostedEvent>} and send them to OpenSearch as a single Bulk API call.
     * Not a setting on {@code transactionListenerContainerFactory} above: that one is also used by
     * {@code NotificationConsumer}, whose listener method takes a single event, not a list --
     * {@code setBatchListener} is a container-factory-wide setting, so every listener sharing a
     * factory gets the same batch/non-batch shape whether its method is ready for it or not.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionPostedEvent> transactionSearchIndexerContainerFactory(
            ConsumerFactory<String, TransactionPostedEvent> transactionConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionPostedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionConsumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(KafkaTopicConfig.PARTITIONS);
        factory.setCommonErrorHandler(searchIndexingErrorHandler());
        return factory;
    }

    /**
     * What the search indexers' listeners do when a batch fails: hand the same batch back after a
     * pause, for as long as it takes. {@code BulkIndexer} throws only for what can succeed later
     * (OpenSearch down, timing out, overloaded) and drops what never can, so waiting for ever is
     * waiting for OpenSearch to come back, and search catches up from Kafka when it does.
     *
     * <p>Spring Kafka's default handler tries ten times without pausing and then skips the batch,
     * which at the 5-second socket timeout gives up on any outage longer than about 50 seconds.
     *
     * <p>Built here for each factory rather than declared as a bean: Boot applies a
     * {@code CommonErrorHandler} bean to its own default listener factory, and this is only for search.
     */
    static DefaultErrorHandler searchIndexingErrorHandler() {
        return new DefaultErrorHandler(searchIndexingBackOff());
    }

    /** One second, doubling, never more than a minute, and never giving up. */
    static BackOff searchIndexingBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(60_000);
        return backOff;
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
        // CustomerSearchIndexer is this factory's only listener (unlike the transaction topic,
        // which NotificationConsumer also consumes -- see transactionSearchIndexerContainerFactory
        // for why that one needed a separate factory), so batch mode can go directly here: takes
        // a batch (List<CustomerChangedEvent>) so a whole poll's worth of events goes to
        // OpenSearch as one Bulk API call, not one HTTP round trip each.
        factory.setBatchListener(true);
        factory.setConcurrency(KafkaTopicConfig.PARTITIONS);
        factory.setCommonErrorHandler(searchIndexingErrorHandler());
        return factory;
    }
}
