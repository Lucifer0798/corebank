package com.corebank.search;

import com.corebank.common.metrics.BackgroundFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;

/**
 * The bulk-indexing call every search indexer makes, and what happens when it fails.
 *
 * <p>What can succeed later is retried; only what never can is dropped. This used to log and drop
 * every failure, on the grounds that search is a downstream projection. But a document dropped
 * during an OpenSearch restart stayed missing from search until someone replayed it by hand, and
 * nothing said it was missing. Every document is keyed by id, so indexing one again overwrites it
 * rather than duplicating it, and retrying is safe.
 *
 * <ul>
 *   <li><b>Retried:</b> the whole call failing, which is OpenSearch unreachable, timing out or
 *       answering 429 or 5xx, and any document refused with 429 or 5xx. This throws, and the
 *       listener's error handler hands the same batch back after a pause (see
 *       {@code KafkaConsumerConfig.searchIndexingErrorHandler}), until it goes through. Anything
 *       not known to be permanent counts as retryable: retrying a permanent failure holds the
 *       partition up and fires an alert, while dropping a transient one is the silent gap this
 *       replaced.</li>
 *   <li><b>Dropped:</b> a document OpenSearch rejects as bad, any other 4xx. Retrying it would
 *       hold up every document behind it for ever. It is logged and counted under
 *       {@code corebank.background.failures{task="search-indexing"}}.</li>
 * </ul>
 */
final class BulkIndexer {

    static final String RETRIES = "corebank.search.index.retries";
    static final String TASK = "search-indexing";

    private final OpenSearchClient client;
    private final Logger log;
    private final String kind;
    private final Counter retries;
    private final Counter dropped;

    /** Takes the caller's logger, so a warning names the indexer it came from. */
    BulkIndexer(OpenSearchClient client, Logger log, String kind, MeterRegistry meterRegistry) {
        this.client = client;
        this.log = log;
        this.kind = kind;
        // Both from zero, so an alert can see the first.
        this.retries = Counter.builder(RETRIES)
                .description("Search index attempts that failed and will be tried again")
                .tag("kind", kind)
                .register(meterRegistry);
        this.dropped = BackgroundFailures.counter(meterRegistry, TASK);
    }

    void index(List<BulkOperation> operations) {
        BulkResponse response;
        try {
            response = client.bulk(BulkRequest.of(b -> b.operations(operations)));
        } catch (OpenSearchException ex) {
            if (isRetryable(ex.status())) {
                throw retry("OpenSearch answered " + ex.status() + ": " + ex.getMessage(), ex);
            }
            dropped.increment(operations.size());
            log.warn("Could not index a batch of {} {}(s) into OpenSearch, and will not retry it: {}",
                    operations.size(), kind, ex.toString());
            return;
        } catch (Exception ex) {
            throw retry(ex.toString(), ex);
        }
        if (!response.errors()) {
            return;
        }
        List<BulkResponseItem> failed = response.items().stream().filter(item -> item.error() != null).toList();
        List<BulkResponseItem> retryable = failed.stream().filter(item -> isRetryable(item.status())).toList();
        if (!retryable.isEmpty()) {
            // The whole batch again: the documents that went in are overwritten with themselves.
            // The ones that will never go in are left to be counted once, on the attempt that ends it.
            BulkResponseItem first = retryable.getFirst();
            throw retry(retryable.size() + " " + kind + "(s) refused, first " + first.id() + " with "
                    + first.status() + ": " + first.error().reason(), null);
        }
        for (BulkResponseItem item : failed) {
            dropped.increment();
            log.warn("Could not index {} {} into OpenSearch, and will not retry it: {}",
                    kind, item.id(), item.error().reason());
        }
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    private SearchIndexingFailedException retry(String why, Exception cause) {
        retries.increment();
        log.warn("Could not index {}(s) into OpenSearch; will try again: {}", kind, why);
        return new SearchIndexingFailedException(why, cause);
    }

    /** Thrown so the listener's error handler delivers the batch again. */
    static final class SearchIndexingFailedException extends RuntimeException {
        SearchIndexingFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
