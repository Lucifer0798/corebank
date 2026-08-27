package com.corebank.customer.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards a customer create/KYC/identity change to Kafka once its database transaction has
 * actually committed. See {@code TransactionEventPublisher} for the identical reasoning behind
 * every choice here: after-commit listening so a rolled-back write never reaches Kafka, and a
 * fire-and-forget send so a broker outage never fails an HTTP request that already succeeded.
 */
@Component
public class CustomerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventPublisher.class);
    public static final String TOPIC = "corebank.customers.changed";

    private final KafkaTemplate<String, CustomerChangedEvent> kafkaTemplate;

    public CustomerEventPublisher(KafkaTemplate<String, CustomerChangedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCustomerChanged(CustomerChangedEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.id().toString(), event).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish customer {} to {}: {}", event.id(), TOPIC, ex.toString());
                } else {
                    log.debug("Published customer {} to {}[{}]", event.id(), TOPIC,
                            result.getRecordMetadata().partition());
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Could not send customer {} to {}: {}", event.id(), TOPIC, ex.toString());
        }
    }
}
