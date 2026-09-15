package com.corebank.search;

import com.corebank.transaction.messaging.TransactionEventPublisher;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import java.util.List;
import java.util.Map;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
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
 * <p>A batch listener rather than one record at a time: whatever arrived in a single poll goes
 * to OpenSearch as one Bulk API call instead of one HTTP round trip per transaction, which is the
 * difference that actually matters once transactions post faster than one at a time. A failed
 * index attempt -- one bad document within an otherwise-successful bulk call, or the whole call
 * failing outright -- is logged and dropped, not retried, the same trade-off the single-record
 * version made: search is a downstream projection, and the ledger these events came from already
 * committed successfully regardless. This means a transient OpenSearch outage leaves a gap in the
 * index rather than catching up automatically once it recovers -- an accepted trade-off for this
 * phase, not an oversight.
 */
@Component
public class TransactionSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(TransactionSearchIndexer.class);

    private final OpenSearchClient client;

    public TransactionSearchIndexer(OpenSearchClient client) {
        this.client = client;
    }

    @KafkaListener(topics = TransactionEventPublisher.TOPIC, groupId = "corebank-search-indexer",
            containerFactory = "transactionSearchIndexerContainerFactory")
    public void onTransactionsPosted(List<TransactionPostedEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        List<BulkOperation> operations = events.stream().map(this::toBulkOperation).toList();
        try {
            BulkResponse response = client.bulk(b -> b.operations(operations));
            if (response.errors()) {
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .forEach(item -> log.warn("Could not index transaction {} into OpenSearch: {}",
                                item.id(), item.error().reason()));
            }
        } catch (Exception ex) {
            log.warn("Could not index a batch of {} transaction(s) into OpenSearch: {}", events.size(), ex.toString());
        }
    }

    private BulkOperation toBulkOperation(TransactionPostedEvent event) {
        List<String> accountNumbers = event.legs().stream().map(TransactionPostedEvent.Leg::accountNumber).toList();
        Map<String, Object> document = Map.of(
                "reference", event.reference(),
                "type", event.type().name(),
                "amount", event.amount(),
                "currency", event.currency(),
                "description", event.description() == null ? "" : event.description(),
                "postedAt", event.postedAt().toString(),
                "accountNumbers", accountNumbers);
        return BulkOperation.of(op -> op.index(idx -> idx
                .index(SearchIndices.TRANSACTIONS)
                .id(event.reference())
                .document(document)));
    }
}
