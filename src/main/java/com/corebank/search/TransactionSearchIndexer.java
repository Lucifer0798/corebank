package com.corebank.search;

import com.corebank.transaction.messaging.TransactionEventPublisher;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import java.util.List;
import java.util.Map;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Indexes every posted transaction into OpenSearch, keyed by the transaction reference so a
 * redelivered message overwrites rather than duplicates. Its own consumer group, separate from
 * {@code TransactionEventLogger}'s -- two independent consumers of the same topic, each with its
 * own offset, is the normal Kafka pattern for adding a second thing that cares about a topic
 * without touching the first.
 *
 * <p>A failed index attempt is logged and dropped, not retried: search is a downstream
 * projection, and the ledger this event came from already committed successfully regardless.
 * This means a transient OpenSearch outage leaves a gap in the index rather than catching up
 * automatically once it recovers -- an accepted trade-off for this phase, not an oversight.
 */
@Component
public class TransactionSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(TransactionSearchIndexer.class);

    private final OpenSearchClient client;

    public TransactionSearchIndexer(OpenSearchClient client) {
        this.client = client;
    }

    @KafkaListener(topics = TransactionEventPublisher.TOPIC, groupId = "corebank-search-indexer",
            containerFactory = "transactionListenerContainerFactory")
    public void onTransactionPosted(TransactionPostedEvent event) {
        try {
            List<String> accountNumbers = event.legs().stream().map(TransactionPostedEvent.Leg::accountNumber).toList();
            Map<String, Object> document = Map.of(
                    "reference", event.reference(),
                    "type", event.type().name(),
                    "amount", event.amount(),
                    "currency", event.currency(),
                    "description", event.description() == null ? "" : event.description(),
                    "postedAt", event.postedAt().toString(),
                    "accountNumbers", accountNumbers);
            client.index(i -> i.index(SearchIndices.TRANSACTIONS).id(event.reference()).document(document));
        } catch (Exception ex) {
            log.warn("Could not index transaction {} into OpenSearch: {}", event.reference(), ex.toString());
        }
    }
}
