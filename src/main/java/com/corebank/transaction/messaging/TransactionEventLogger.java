package com.corebank.transaction.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Stands in for whatever actually consumes this topic in a later phase -- a notifications
 * service, a fraud-screening pipeline, an audit export to Redshift. For now it only proves the
 * round trip: every posting reaches Kafka, and something on the other side reads it back.
 */
@Component
public class TransactionEventLogger {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventLogger.class);

    @KafkaListener(topics = TransactionEventPublisher.TOPIC, groupId = "corebank-app")
    public void onTransactionPosted(TransactionPostedEvent event) {
        log.info("[{}] {} {} {} legs={}", event.reference(), event.type(), event.amount(),
                event.currency(), event.legs().size());
    }
}
