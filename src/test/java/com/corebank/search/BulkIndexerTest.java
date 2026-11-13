package com.corebank.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.common.metrics.BackgroundFailures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.slf4j.LoggerFactory;

/**
 * What is retried and what is dropped. Throwing is what makes the listener's error handler deliver
 * the batch again, so "retried" here means "throws", and "dropped" means "returns, and counts it".
 */
class BulkIndexerTest {

    private final OpenSearchClient client = mock(OpenSearchClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final BulkIndexer indexer = new BulkIndexer(client, LoggerFactory.getLogger(BulkIndexerTest.class),
            "transaction", meters);

    private static final List<BulkOperation> TWO = List.of(operation("TXN-1"), operation("TXN-2"));

    private static BulkOperation operation(String id) {
        return BulkOperation.of(op -> op.index(idx -> idx.index("transactions").id(id).document(Map.of("reference", id))));
    }

    private static BulkResponseItem ok(String id) {
        return BulkResponseItem.of(item -> item.operationType(OperationType.Index).index("transactions").id(id).status(201));
    }

    private static BulkResponseItem refused(String id, int status) {
        return BulkResponseItem.of(item -> item.operationType(OperationType.Index).index("transactions").id(id)
                .status(status).error(error -> error.type(status == 429 ? "es_rejected_execution_exception"
                        : "mapper_parsing_exception").reason("refused with " + status)));
    }

    private static BulkResponse response(BulkResponseItem... items) {
        boolean errors = List.of(items).stream().anyMatch(item -> item.error() != null);
        return BulkResponse.of(r -> r.errors(errors).took(3).items(List.of(items)));
    }

    private static OpenSearchException answered(int status) {
        return new OpenSearchException(ErrorResponse.of(r -> r.status(status)
                .error(error -> error.type(status == 429 ? "too_many_requests" : "some_exception").reason("status " + status))));
    }

    private double retries() {
        return meters.get(BulkIndexer.RETRIES).tag("kind", "transaction").counter().count();
    }

    private double dropped() {
        return meters.get(BackgroundFailures.METRIC).tag("task", "search-indexing").counter().count();
    }

    @Test
    @DisplayName("both counters exist at zero before anything has failed")
    void countersStartAtZero() {
        assertThat(retries()).isZero();
        assertThat(dropped()).isZero();
    }

    @Test
    @DisplayName("a batch that goes in is neither retried nor dropped")
    void aCleanBatch() throws IOException {
        when(client.bulk(any(BulkRequest.class))).thenReturn(response(ok("TXN-1"), ok("TXN-2")));

        assertThatCode(() -> indexer.index(TWO)).doesNotThrowAnyException();

        assertThat(retries()).isZero();
        assertThat(dropped()).isZero();
    }

    @Test
    @DisplayName("OpenSearch unreachable or timing out: retried, not dropped")
    void unreachableIsRetried() throws IOException {
        when(client.bulk(any(BulkRequest.class))).thenThrow(new SocketTimeoutException("5,000 milliseconds timeout"));

        assertThatThrownBy(() -> indexer.index(TWO)).isInstanceOf(BulkIndexer.SearchIndexingFailedException.class);

        assertThat(retries()).isEqualTo(1);
        assertThat(dropped()).isZero();
    }

    @Test
    @DisplayName("OpenSearch overloaded or failing, 429 or 5xx for the whole call: retried")
    void overloadedIsRetried() throws IOException {
        when(client.bulk(any(BulkRequest.class))).thenThrow(answered(503)).thenThrow(answered(429));

        assertThatThrownBy(() -> indexer.index(TWO)).isInstanceOf(BulkIndexer.SearchIndexingFailedException.class);
        assertThatThrownBy(() -> indexer.index(TWO)).isInstanceOf(BulkIndexer.SearchIndexingFailedException.class);

        assertThat(retries()).isEqualTo(2);
        assertThat(dropped()).isZero();
    }

    @Test
    @DisplayName("a whole call refused as bad, a 4xx other than 429: dropped and counted, not retried for ever")
    void aBadRequestIsDropped() throws IOException {
        when(client.bulk(any(BulkRequest.class))).thenThrow(answered(400));

        assertThatCode(() -> indexer.index(TWO)).doesNotThrowAnyException();

        assertThat(dropped()).isEqualTo(2);
        assertThat(retries()).isZero();
    }

    @Test
    @DisplayName("one document refused as bad: dropped and counted, and the rest are not held up")
    void aBadDocumentIsDropped() throws IOException {
        when(client.bulk(any(BulkRequest.class))).thenReturn(response(ok("TXN-1"), refused("TXN-2", 400)));

        assertThatCode(() -> indexer.index(TWO)).doesNotThrowAnyException();

        assertThat(dropped()).isEqualTo(1);
        assertThat(retries()).isZero();
    }

    @Test
    @DisplayName("one document refused for now, 429: the batch is retried, and the bad one is not counted yet")
    void aDocumentRefusedForNowIsRetried() throws IOException {
        when(client.bulk(any(BulkRequest.class)))
                .thenReturn(response(refused("TXN-1", 400), refused("TXN-2", 429)));

        assertThatThrownBy(() -> indexer.index(TWO)).isInstanceOf(BulkIndexer.SearchIndexingFailedException.class);

        assertThat(retries()).isEqualTo(1);
        // Counted on the attempt that settles it, not once per retry.
        assertThat(dropped()).isZero();
    }
}
