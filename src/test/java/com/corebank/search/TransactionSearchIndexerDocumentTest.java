package com.corebank.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.corebank.account.domain.EntryDirection;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The search document an event becomes, and in particular what happens to an event written before
 * {@code status} existed.
 *
 * <p>That case is not hypothetical. The topic already holds messages from every transaction posted
 * before this change, and a consumer that restarts from an earlier offset, or the admin replay
 * endpoint, will hand them to the indexer again. They deserialize with {@code status} null.
 */
class TransactionSearchIndexerDocumentTest {

    private static TransactionPostedEvent event(TransactionStatus status) {
        return new TransactionPostedEvent(
                "TXN-20260401-AAAAAAAA", TransactionType.DEPOSIT, status,
                new BigDecimal("250.00"), "INR", "Counter", Instant.parse("2026-04-01T10:00:00Z"),
                List.of(new TransactionPostedEvent.Leg("GL0000000001", EntryDirection.DEBIT,
                                new BigDecimal("250.00"), new BigDecimal("250.00")),
                        new TransactionPostedEvent.Leg("100100000001", EntryDirection.CREDIT,
                                new BigDecimal("250.00"), new BigDecimal("250.00"))));
    }

    @Test
    @DisplayName("a reversed transaction is indexed as REVERSED")
    void aReversedTransactionIsIndexedAsSuch() {
        assertThat(TransactionSearchIndexer.documentFor(event(TransactionStatus.REVERSED)))
                .containsEntry("status", "REVERSED");
    }

    @Test
    @DisplayName("a message from before status existed is indexed as POSTED, not rejected")
    void aLegacyMessageReadsAsPosted() {
        // Calling status().name() on this would throw, and because the indexer works in batches, one
        // old message would hold up every new one queued behind it. A missing status means POSTED:
        // nothing sent one before reversals propagated, and every message of that era described a
        // transaction at the moment it was posted.
        TransactionPostedEvent legacy = event(null);

        assertThatCode(() -> TransactionSearchIndexer.documentFor(legacy)).doesNotThrowAnyException();
        assertThat(TransactionSearchIndexer.documentFor(legacy)).containsEntry("status", "POSTED");
    }

    @Test
    @DisplayName("the document is keyed by reference, which is what lets a re-publish overwrite it")
    void theDocumentCarriesItsReference() {
        // Propagation depends on this. A reversal re-publishes the original under the same reference,
        // and the indexer upserts on it -- so the reversed document replaces the posted one instead of
        // sitting alongside it as a second, contradictory hit.
        assertThat(TransactionSearchIndexer.documentFor(event(TransactionStatus.POSTED)))
                .containsEntry("reference", "TXN-20260401-AAAAAAAA");
    }
}
