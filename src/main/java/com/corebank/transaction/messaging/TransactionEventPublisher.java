package com.corebank.transaction.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards a posted transaction to Kafka once its database transaction has actually committed.
 *
 * <p>{@code TransactionService} publishes a {@link TransactionPostedEvent} as an ordinary Spring
 * application event from inside the same {@code @Transactional} method that writes the ledger
 * entries. Listening at {@link TransactionPhase#AFTER_COMMIT} rather than handling it inline
 * means a transaction that gets rolled back -- an optimistic-lock failure, a constraint
 * violation caught by the global handler -- never reaches Kafka at all.
 *
 * <p>The send is fire-and-forget: a broker outage degrades to "this posting didn't get
 * published yet" rather than failing the HTTP request that already succeeded and committed.
 * Phase 1's ledger remains the single source of truth; this topic is a downstream feed of it,
 * not a second copy of it that has to agree.
 */
@Component
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);
    public static final String TOPIC = "corebank.transactions.posted";

    private final KafkaTemplate<String, TransactionPostedEvent> kafkaTemplate;

    public TransactionEventPublisher(KafkaTemplate<String, TransactionPostedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionPosted(TransactionPostedEvent event) {
        try {
            // send() itself can block for up to producer.properties.max.block.ms and throw
            // synchronously if it can't obtain cluster metadata in that time -- that failure
            // never reaches the future below, so it needs its own catch.
            kafkaTemplate.send(TOPIC, event.reference(), event).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish {} to {}: {}", event.reference(), TOPIC, ex.toString());
                } else {
                    log.debug("Published {} to {}[{}]", event.reference(), TOPIC,
                            result.getRecordMetadata().partition());
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Could not send {} to {}: {}", event.reference(), TOPIC, ex.toString());
        }
    }
}
