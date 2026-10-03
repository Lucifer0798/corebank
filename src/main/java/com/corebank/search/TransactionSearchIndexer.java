package com.corebank.search;

import com.corebank.transaction.messaging.TransactionEventPublisher;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import java.util.List;
import java.util.Map;
import org.opensearch.client.opensearch.OpenSearchClient;
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
 * difference that actually matters once transactions post faster than one at a time. See
 * {@link BulkIndexer} -- shared with {@code CustomerSearchIndexer} -- for the failure handling: a
 * failed index attempt is logged and dropped, not retried, since search is a downstream
 * projection and the ledger these events came from already committed successfully regardless.
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
        BulkIndexer.index(client, log, "transaction", operations);
    }

    private BulkOperation toBulkOperation(TransactionPostedEvent event) {
        Map<String, Object> document = documentFor(event);
        return BulkOperation.of(op -> op.index(idx -> idx
                .index(SearchIndices.TRANSACTIONS)
                .id(event.reference())
                .document(document)));
    }

    /**
     * The search document for one event.
     *
     * <p>{@code status} is new, and the reason a reversed transaction now reads as reversed: a
     * reversal re-publishes the original carrying REVERSED, and because the document is keyed by
     * reference this overwrites the one written when it was posted.
     *
     * <p>Read through {@code statusOrPosted()}, never {@code status()} directly. Messages already
     * in the topic predate the field and deserialize with it null, and calling {@code name()} on
     * that would fail the whole batch -- one old message holding up every new one behind it.
     * Package-private so that case can be tested without a broker.
     */
    static Map<String, Object> documentFor(TransactionPostedEvent event) {
        List<String> accountNumbers = event.legs().stream().map(TransactionPostedEvent.Leg::accountNumber).toList();
        return Map.of(
                "reference", event.reference(),
                "type", event.type().name(),
                "status", event.statusOrPosted().name(),
                "amount", event.amount(),
                "currency", event.currency(),
                "description", event.description() == null ? "" : event.description(),
                "postedAt", event.postedAt().toString(),
                "accountNumbers", accountNumbers);
    }
}
