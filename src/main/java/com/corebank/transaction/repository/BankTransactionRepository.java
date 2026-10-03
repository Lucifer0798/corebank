package com.corebank.transaction.repository;

import com.corebank.transaction.domain.BankTransaction;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    Optional<BankTransaction> findByReference(String reference);

    /** Backs {@code OutboxBackfillService}'s transaction replay: a transaction's ledger entries
     *  never change after posting, so postedAt alone identifies every transaction a replay window
     *  should cover. Reversal changes the original's {@code status}, and since status is part of
     *  {@code TransactionPostedEvent}, a replay re-publishes each transaction as it stands now -- a
     *  reversed one goes out as REVERSED. That is what repairs search documents written before
     *  reversals propagated. The correcting legs arrive as their own transaction, with their own
     *  postedAt, which this window picks up in the ordinary way.
     *  Paged rather than loaded whole -- a wide window on a large table would otherwise pull an
     *  unbounded result set into heap. A {@link Slice}, not a {@link org.springframework.data.domain.Page},
     *  since the caller only needs to know whether another page follows, not the total count. */
    Slice<BankTransaction> findByPostedAtBetween(Instant since, Instant until, Pageable pageable);
}
