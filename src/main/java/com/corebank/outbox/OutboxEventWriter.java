package com.corebank.outbox;

import com.corebank.outbox.domain.OutboxAggregateType;
import com.corebank.outbox.domain.OutboxEvent;
import com.corebank.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a domain event into a durable outbox row. Called from a plain (non-transactional-phase)
 * {@code @EventListener} inside the same {@code @Transactional} method that wrote the ledger or
 * customer change the event describes -- see {@code TransactionEventPublisher} and
 * {@code CustomerEventPublisher} -- so the write is atomic with that change: both commit
 * together, or an exception here (a serialization bug, say) rolls both back together rather than
 * silently posting money with no event ever following it.
 *
 * <p>Nothing here talks to Kafka. That happens later, out of band, in {@link OutboxRelay}.
 */
@Service
public class OutboxEventWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void write(OutboxAggregateType aggregateType, String topic, String key, Object payload) {
        String json = objectMapper.writeValueAsString(payload);
        repository.save(OutboxEvent.create(aggregateType, topic, key, json));
    }
}
