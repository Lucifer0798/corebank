package com.corebank.search;

import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.customer.messaging.CustomerEventPublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.MeterRegistry;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Indexes a customer into OpenSearch on create and on every KYC/status change, keyed by the
 * customer id so a later change overwrites the earlier document rather than creating a second
 * one. See {@code TransactionSearchIndexer} for the same reasoning behind the batch listener (one
 * Bulk API call per poll rather than one HTTP round trip per event), and {@link BulkIndexer} for
 * the failure handling: retried until it goes through, unless OpenSearch rejects a document as bad.
 */
@Component
public class CustomerSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(CustomerSearchIndexer.class);

    private final BulkIndexer bulkIndexer;

    public CustomerSearchIndexer(OpenSearchClient client, MeterRegistry meterRegistry) {
        this.bulkIndexer = new BulkIndexer(client, log, "customer", meterRegistry);
    }

    @KafkaListener(topics = CustomerEventPublisher.TOPIC, groupId = "corebank-search-indexer",
            containerFactory = "customerListenerContainerFactory")
    public void onCustomersChanged(List<CustomerChangedEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        List<BulkOperation> operations = events.stream().map(this::toBulkOperation).toList();
        bulkIndexer.index(operations);
    }

    private BulkOperation toBulkOperation(CustomerChangedEvent event) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", event.id().toString());
        document.put("customerNumber", event.customerNumber());
        document.put("firstName", event.firstName());
        document.put("lastName", event.lastName());
        document.put("email", event.email());
        document.put("phone", event.phone() == null ? "" : event.phone());
        document.put("kycStatus", event.kycStatus().name());
        document.put("status", event.status().name());
        document.put("changedAt", event.changedAt().toString());
        return BulkOperation.of(op -> op.index(idx -> idx
                .index(SearchIndices.CUSTOMERS)
                .id(event.id().toString())
                .document(document)));
    }
}
