package com.corebank.search.dto;

import com.corebank.transaction.domain.TransactionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A search result, not {@code TransactionResponse} reused: the index document's shape is free to
 * evolve independently of both the ledger and the HTTP API that reads it directly.
 * {@code ignoreUnknown = true} is what actually makes that true in practice -- see
 * {@code CustomerSearchHit} for the real bug hitting the sibling DTO without it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionSearchHit(
        String reference,
        TransactionType type,
        /*
         * Null on a document indexed before status existed. Read it as POSTED, the same rule the
         * indexer applies to old messages -- a re-index from the admin replay endpoint fills it in.
         */
        com.corebank.transaction.domain.TransactionStatus status,
        BigDecimal amount,
        String currency,
        String description,
        Instant postedAt,
        List<String> accountNumbers) {
}
