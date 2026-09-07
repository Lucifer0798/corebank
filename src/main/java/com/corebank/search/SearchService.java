package com.corebank.search;

import com.corebank.common.exception.SearchUnavailableException;
import com.corebank.search.dto.CustomerSearchHit;
import com.corebank.search.dto.SearchResponse;
import com.corebank.search.dto.TransactionSearchHit;
import com.corebank.transaction.domain.TransactionType;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.springframework.stereotype.Service;

/**
 * OpenSearch is a downstream read projection (see {@code TransactionSearchIndexer}), so a failed
 * query here becomes {@link SearchUnavailableException} -- a clean 503 -- rather than an
 * unhandled 500. Nothing else in the application depends on search being reachable.
 *
 * <p>Both a transport failure ({@link IOException}, e.g. the broker is unreachable) and a
 * server-side error the client turns into {@link OpenSearchException} (e.g. querying an index
 * that does not exist yet -- exactly the state right after {@code SearchIndexInitializer}
 * creates one and before it is backfilled) are caught here. The client throws the latter as an
 * unchecked exception, so missing it would have meant one specific, foreseeable OpenSearch
 * failure mode bypassing this class's whole reason for existing and reaching the caller as a
 * bare 500 instead.
 */
@Service
public class SearchService {

    private final OpenSearchClient client;

    public SearchService(OpenSearchClient client) {
        this.client = client;
    }

    public SearchResponse<TransactionSearchHit> searchTransactions(
            String q, TransactionType type, BigDecimal minAmount, BigDecimal maxAmount,
            Instant from, Instant to, int page, int size) {

        List<Query> must = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            must.add(Query.of(m -> m.match(t -> t.field("description").query(fv -> fv.stringValue(q)))));
        }
        if (type != null) {
            must.add(Query.of(m -> m.term(t -> t.field("type").value(fv -> fv.stringValue(type.name())))));
        }
        if (minAmount != null || maxAmount != null) {
            must.add(Query.of(m -> m.range(r -> {
                r.field("amount");
                if (minAmount != null) {
                    r.gte(JsonData.of(minAmount.doubleValue()));
                }
                if (maxAmount != null) {
                    r.lte(JsonData.of(maxAmount.doubleValue()));
                }
                return r;
            })));
        }
        if (from != null || to != null) {
            must.add(Query.of(m -> m.range(r -> {
                r.field("postedAt");
                if (from != null) {
                    r.gte(JsonData.of(from.toString()));
                }
                if (to != null) {
                    r.lte(JsonData.of(to.toString()));
                }
                return r;
            })));
        }

        Query query = must.isEmpty()
                ? Query.of(m -> m.matchAll(a -> a))
                : Query.of(m -> m.bool(b -> b.must(must)));

        try {
            org.opensearch.client.opensearch.core.SearchResponse<TransactionSearchHit> response = client.search(
                    s -> s.index(SearchIndices.TRANSACTIONS).query(query).from(page * size).size(size),
                    TransactionSearchHit.class);
            return toSearchResponse(response, page, size);
        } catch (IOException | OpenSearchException ex) {
            throw new SearchUnavailableException(ex);
        }
    }

    public SearchResponse<CustomerSearchHit> searchCustomers(String q, int page, int size) {
        // customerNumber is keyword-mapped (see SearchIndexInitializer), so folding it into the
        // multi_match above with the text fields only ever matched a query that was the *entire*
        // field value, case-sensitively -- a customer typing part of a number, or lower-casing it,
        // got zero hits. It gets its own case-insensitive substring match instead, alongside the
        // free-text match over the name/email fields.
        Query query = (q == null || q.isBlank())
                ? Query.of(m -> m.matchAll(a -> a))
                : Query.of(m -> m.bool(b -> b
                        .should(Query.of(s -> s.multiMatch(mm -> mm
                                .fields("firstName", "lastName", "email")
                                .query(q))))
                        .should(Query.of(s -> s.wildcard(w -> w
                                .field("customerNumber")
                                .value("*" + escapeWildcard(q) + "*")
                                .caseInsensitive(true))))
                        .minimumShouldMatch("1")));

        try {
            org.opensearch.client.opensearch.core.SearchResponse<CustomerSearchHit> response = client.search(
                    s -> s.index(SearchIndices.CUSTOMERS).query(query).from(page * size).size(size),
                    CustomerSearchHit.class);
            return toSearchResponse(response, page, size);
        } catch (IOException | OpenSearchException ex) {
            throw new SearchUnavailableException(ex);
        }
    }

    /** Escapes wildcard-query metacharacters so a customer number containing them is matched literally. */
    private static String escapeWildcard(String q) {
        return q.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    private <T> SearchResponse<T> toSearchResponse(org.opensearch.client.opensearch.core.SearchResponse<T> response,
                                                     int page, int size) {
        List<T> hits = response.hits().hits().stream().map(org.opensearch.client.opensearch.core.search.Hit::source).toList();
        long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
        return new SearchResponse<>(hits, total, page, size);
    }
}
