package com.corebank.config;

import com.corebank.common.metrics.BackgroundFailures;
import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.transaction.CannotCreateTransactionException;
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
 *
 * <p>Every value deserializer is wrapped in an {@link ErrorHandlingDeserializer}. Without it, one
 * message that is not valid JSON for its type stopped every consumer of its topic for good: the
 * Kafka client throws on poll, before any listener or error handler sees a record, so the
 * container polled the same offset in a tight loop, logging about 367,000 errors a minute per
 * partition, while notifications and search for everything behind it waited. Wrapped, a bad
 * message arrives as a failed record that can be skipped, and is counted under
 * {@code corebank.background.failures{task="malformed-events"}}.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    static final String MALFORMED = "malformed-events";
    static final String NOTIFICATIONS = "notifications";

    @Bean
    public ConsumerFactory<String, TransactionPostedEvent> transactionConsumerFactory(
            KafkaProperties kafkaProperties) {
        JsonDeserializer<TransactionPostedEvent> deserializer = new JsonDeserializer<>(TransactionPostedEvent.class);
        deserializer.addTrustedPackages("com.corebank.transaction.messaging");
        return new DefaultKafkaConsumerFactory<>(
                consumerProperties(kafkaProperties), new StringDeserializer(), new ErrorHandlingDeserializer<>(deserializer));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionPostedEvent> transactionListenerContainerFactory(
            ConsumerFactory<String, TransactionPostedEvent> transactionConsumerFactory, MeterRegistry meterRegistry) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionPostedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionConsumerFactory);
        // Matches KafkaTopicConfig.PARTITIONS: one consumer thread per partition, so this
        // listener's own group can actually consume in parallel rather than leaving idle threads.
        factory.setConcurrency(KafkaTopicConfig.PARTITIONS);
        factory.setCommonErrorHandler(notificationErrorHandler(meterRegistry));
        return factory;
    }

    /**
     * What {@code NotificationConsumer} does when writing a notification fails.
     *
     * <p>It used to be Spring Kafka's default: ten attempts with no pause, then skip. A database
     * restart or a full connection pool lasting longer than those ten attempts meant every posting
     * in that window was never announced, and nothing counted it.
     *
     * <ul>
     *   <li><b>The database being unavailable</b> is waited out: the same record again after a
     *       pause of a second, doubling to a minute, for as long as it takes. Writing a
     *       notification is one transaction and a redelivery is recognised and skipped, so trying
     *       again cannot tell anyone twice.</li>
     *   <li><b>Anything else</b>, a bug or a malformed message, will fail the same way every time,
     *       and waiting would hold up every notification behind it. It is skipped at once, logged,
     *       and counted, so the alert on {@code corebank.background.failures} fires.</li>
     * </ul>
     */
    static DefaultErrorHandler notificationErrorHandler(MeterRegistry meterRegistry) {
        Counter failed = BackgroundFailures.counter(meterRegistry, NOTIFICATIONS);
        Counter malformed = BackgroundFailures.counter(meterRegistry, MALFORMED);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            if (causedBy(ex, DeserializationException.class)) {
                malformed.increment();
                log.error("Skipped a malformed message on {} partition {} offset {}: {}",
                        record.topic(), record.partition(), record.offset(), rootMessage(ex));
            } else {
                failed.increment();
                log.error("Could not write the notifications for {}, and skipped it: {}", describe(record), rootMessage(ex), ex);
            }
        }, backOffUntilItWorks());
        // Nothing is retried unless it is listed here: a failure nobody anticipated is far more
        // likely to repeat than to clear, and repeating holds up every notification behind it.
        handler.defaultFalse();
        handler.addRetryableExceptions(TransientDataAccessException.class, RecoverableDataAccessException.class,
                DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
                SQLTransientException.class, SQLRecoverableException.class);
        return handler;
    }

    private static String describe(ConsumerRecord<?, ?> record) {
        return record.value() instanceof TransactionPostedEvent event
                ? event.reference()
                : record.topic() + " partition " + record.partition() + " offset " + record.offset();
    }

    static boolean causedBy(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable thrown) {
        Throwable root = thrown;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
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
        return new DefaultErrorHandler(backOffUntilItWorks());
    }

    /** One second, doubling, never more than a minute, and never giving up. */
    static BackOff backOffUntilItWorks() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(60_000);
        return backOff;
    }

    @Bean
    public ConsumerFactory<String, CustomerChangedEvent> customerConsumerFactory(KafkaProperties kafkaProperties) {
        JsonDeserializer<CustomerChangedEvent> deserializer = new JsonDeserializer<>(CustomerChangedEvent.class);
        deserializer.addTrustedPackages("com.corebank.customer.messaging");
        return new DefaultKafkaConsumerFactory<>(
                consumerProperties(kafkaProperties), new StringDeserializer(), new ErrorHandlingDeserializer<>(deserializer));
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
