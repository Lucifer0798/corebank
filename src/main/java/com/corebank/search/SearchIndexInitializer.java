package com.corebank.search;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the two search indices on startup if they don't already exist. Runs after the
 * application is fully up ({@link org.springframework.boot.context.event.ApplicationReadyEvent}),
 * not during bean construction, and never fails startup on error: search is a downstream
 * projection, the same status Kafka topics already have here, so an OpenSearch outage at boot
 * should leave search unavailable, not take the whole application down with it.
 */
@Component
public class SearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexInitializer.class);

    private final OpenSearchClient client;

    public SearchIndexInitializer(OpenSearchClient client) {
        this.client = client;
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void ensureIndices() {
        try {
            ensureIndex(SearchIndices.TRANSACTIONS, transactionsMapping());
            ensureIndex(SearchIndices.CUSTOMERS, customersMapping());
        } catch (Exception ex) {
            log.warn("Could not ensure OpenSearch indices exist -- search will be unavailable until "
                    + "this is resolved: {}", ex.toString());
        }
    }

    private void ensureIndex(String name, TypeMapping mapping) throws IOException {
        boolean exists = client.indices().exists(r -> r.index(name)).value();
        if (!exists) {
            client.indices().create(c -> c.index(name).mappings(mapping));
            log.info("Created OpenSearch index {}", name);
        }
    }

    private TypeMapping transactionsMapping() {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("reference", Property.of(p -> p.keyword(k -> k)));
        properties.put("type", Property.of(p -> p.keyword(k -> k)));
        properties.put("amount", Property.of(p -> p.double_(d -> d)));
        properties.put("currency", Property.of(p -> p.keyword(k -> k)));
        properties.put("description", Property.of(p -> p.text(t -> t)));
        properties.put("postedAt", Property.of(p -> p.date(d -> d)));
        properties.put("accountNumbers", Property.of(p -> p.keyword(k -> k)));
        return TypeMapping.of(m -> m.properties(properties));
    }

    private TypeMapping customersMapping() {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("id", Property.of(p -> p.keyword(k -> k)));
        properties.put("customerNumber", Property.of(p -> p.keyword(k -> k)));
        properties.put("firstName", Property.of(p -> p.text(t -> t)));
        properties.put("lastName", Property.of(p -> p.text(t -> t)));
        properties.put("email", Property.of(p -> p.text(t -> t)));
        properties.put("phone", Property.of(p -> p.keyword(k -> k)));
        properties.put("kycStatus", Property.of(p -> p.keyword(k -> k)));
        properties.put("status", Property.of(p -> p.keyword(k -> k)));
        properties.put("changedAt", Property.of(p -> p.date(d -> d)));
        return TypeMapping.of(m -> m.properties(properties));
    }
}
