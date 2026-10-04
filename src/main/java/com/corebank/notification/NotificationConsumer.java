package com.corebank.notification;

import com.corebank.notification.service.NotificationService;
import com.corebank.transaction.messaging.TransactionEventPublisher;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The first real consumer of {@code corebank.transactions.posted}, replacing the logger that only
 * ever proved the round trip.
 *
 * <p>Its group id is the logger's, {@code corebank-app}, and that is not incidental. The topic is
 * read with {@code auto-offset-reset: earliest}, so a consumer under a <em>new</em> group would have
 * no committed offset and start from the beginning -- sending every customer an alert for every
 * transaction they ever made, all at once, on first deploy. Inheriting the logger's group means
 * inheriting its position: this picks up exactly where the logger left off.
 *
 * <p>Thin on purpose. Everything that decides what a customer is told lives in
 * {@link NotificationService}, where it can be tested with real events and no broker.
 */
@Component
public class NotificationConsumer {

    private final NotificationService notificationService;

    public NotificationConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = TransactionEventPublisher.TOPIC, groupId = "corebank-app",
            containerFactory = "transactionListenerContainerFactory")
    public void onTransactionPosted(TransactionPostedEvent event) {
        notificationService.handle(event);
    }
}
