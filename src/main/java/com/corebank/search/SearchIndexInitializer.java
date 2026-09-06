package com.corebank.search;

import com.corebank.outbox.OutboxBackfillService;
import java.io.IOException;
import java.time.Instant;
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
 *
 * <p>An index that did not already exist is backfilled from the ledger/customer tables the
 * instant it is created, through the same {@link OutboxBackfillService} path the admin replay
 * endpoints use. Without this, OpenSearch losing its data -- a wiped volume, a fresh environment,
 * the index dropped by hand -- would leave search silently and permanently empty for every record
 * that existed before the loss: nothing else in this application ever re-sends an event for a
 * customer or transaction that already exists, since the indexers only ever consume what Kafka
 * hands them going forward. "The index did not exist yet" is exactly the signal that this is a
 * fresh start needing a full backfill, not a routine restart that should leave existing data alone.
 */
@Component
public class SearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexInitializer.class);

    private final OpenSearchClient client;
    private final OutboxBackfillService backfillService;

    public SearchIndexInitializer(OpenSearchClient client, OutboxBackfillService backfillService) {
        this.client = client;
        this.backfillService = backfillService;
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void ensureIndices() {
        try {
            if (ensureIndex(SearchIndices.TRANSACTIONS, transactionsMapping())) {
                int count = backfillService.replayTransactions(Instant.EPOCH, Instant.now());
                log.info("Backfilled {} transaction(s) into the newly created search index", count);
            }
            if (ensureIndex(SearchIndices.CUSTOMERS, customersMapping())) {
                int count = backfillService.replayCustomers(Instant.EPOCH, Instant.now());
                log.info("Backfilled {} customer(s) into the newly created search index", count);
            }
        } catch (Exception ex) {
            log.warn("Could not ensure OpenSearch indices exist -- search will be unavailable until "
                    + "this is resolved: {}", ex.toString());
        }
    }

    /** @return true if the index did not already exist and was just created. */
    private boolean ensureIndex(String name, TypeMapping mapping) throws IOException {
        boolean exists = client.indices().exists(r -> r.index(name)).value();
        if (!exists) {
            client.indices().create(c -> c.index(name).mappings(mapping));
            log.info("Created OpenSearch index {}", name);
            return true;
        }
        return false;
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
