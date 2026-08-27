package com.corebank.search;

import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.customer.messaging.CustomerEventPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Indexes a customer into OpenSearch on create and on every KYC/status change, keyed by the
 * customer id so a later change overwrites the earlier document rather than creating a second
 * one. See {@code TransactionSearchIndexer} for the same reasoning behind the failure handling
 * here: a failed index attempt is logged and dropped, not retried.
 */
@Component
public class CustomerSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(CustomerSearchIndexer.class);

    private final OpenSearchClient client;

    public CustomerSearchIndexer(OpenSearchClient client) {
        this.client = client;
    }

    @KafkaListener(topics = CustomerEventPublisher.TOPIC, groupId = "corebank-search-indexer",
            containerFactory = "customerListenerContainerFactory")
    public void onCustomerChanged(CustomerChangedEvent event) {
        try {
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
            client.index(i -> i.index(SearchIndices.CUSTOMERS).id(event.id().toString()).document(document));
        } catch (Exception ex) {
            log.warn("Could not index customer {} into OpenSearch: {}", event.id(), ex.toString());
        }
    }
}
