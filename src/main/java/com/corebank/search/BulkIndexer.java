package com.corebank.search;

import java.util.List;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.slf4j.Logger;

/**
 * The bulk-indexing call and its failure handling every {@code @KafkaListener}-driven search
 * indexer needs, factored out so the current two indexers -- and any future one -- share it
 * rather than reimplementing the same try/catch. A failed index attempt -- one bad document
 * within an otherwise-successful bulk call, or the whole call failing outright -- is logged and
 * dropped, not retried: search is a downstream projection, and whatever produced these events
 * already committed successfully regardless.
 *
 * <p>Takes the caller's own {@link Logger} rather than owning one itself, so a warning still
 * attributes to {@code TransactionSearchIndexer} or {@code CustomerSearchIndexer}, not to this
 * shared helper.
 */
final class BulkIndexer {

    private BulkIndexer() {
    }

    static void index(OpenSearchClient client, Logger log, String kind, List<BulkOperation> operations) {
        try {
            BulkResponse response = client.bulk(b -> b.operations(operations));
            if (response.errors()) {
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .forEach(item -> log.warn("Could not index {} {} into OpenSearch: {}",
                                kind, item.id(), item.error().reason()));
            }
        } catch (Exception ex) {
            log.warn("Could not index a batch of {} {}(s) into OpenSearch: {}", operations.size(), kind, ex.toString());
        }
    }
}
