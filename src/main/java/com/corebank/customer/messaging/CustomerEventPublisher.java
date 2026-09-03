package com.corebank.customer.messaging;

import com.corebank.outbox.OutboxEventWriter;
import com.corebank.outbox.domain.OutboxAggregateType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns a customer create/KYC/identity change into a durable outbox row. See
 * {@code TransactionEventPublisher} for the identical reasoning behind every choice here,
 * including why this is a plain {@link EventListener} in the same transaction rather than an
 * {@code AFTER_COMMIT} one.
 */
@Component
public class CustomerEventPublisher {

    public static final String TOPIC = "corebank.customers.changed";

    private final OutboxEventWriter outbox;

    public CustomerEventPublisher(OutboxEventWriter outbox) {
        this.outbox = outbox;
    }

    @EventListener
    public void onCustomerChanged(CustomerChangedEvent event) {
        outbox.write(OutboxAggregateType.CUSTOMER, TOPIC, event.id().toString(), event);
    }
}
