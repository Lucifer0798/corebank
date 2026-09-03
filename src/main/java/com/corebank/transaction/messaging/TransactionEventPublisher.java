package com.corebank.transaction.messaging;

import com.corebank.outbox.OutboxEventWriter;
import com.corebank.outbox.domain.OutboxAggregateType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns a posted transaction into a durable outbox row, in the same transaction as the ledger
 * write it describes.
 *
 * <p>{@code TransactionService} publishes a {@link TransactionPostedEvent} as an ordinary Spring
 * application event from inside the same {@code @Transactional} method that writes the ledger
 * entries. This listens with a plain {@link EventListener} rather than
 * {@code @TransactionalEventListener(AFTER_COMMIT)} on purpose: the outbox write has to happen
 * <em>inside</em> the original transaction, not after it, so the two commit or roll back
 * together. {@code AFTER_COMMIT} was right for sending straight to Kafka -- it kept a rolled-back
 * posting from ever reaching the topic -- but it is exactly wrong here, since a plain
 * after-commit write reopens the same durability gap this whole mechanism exists to close: a
 * crash between "ledger committed" and "outbox row committed" would lose the event again, just
 * moved to a different failure window.
 *
 * <p>Actually reaching Kafka is {@link com.corebank.outbox.OutboxRelay}'s job, on its own
 * schedule, so a broker outage now delays delivery rather than losing the event outright.
 */
@Component
public class TransactionEventPublisher {

    public static final String TOPIC = "corebank.transactions.posted";

    private final OutboxEventWriter outbox;

    public TransactionEventPublisher(OutboxEventWriter outbox) {
        this.outbox = outbox;
    }

    @EventListener
    public void onTransactionPosted(TransactionPostedEvent event) {
        outbox.write(OutboxAggregateType.TRANSACTION, TOPIC, event.reference(), event);
    }
}
