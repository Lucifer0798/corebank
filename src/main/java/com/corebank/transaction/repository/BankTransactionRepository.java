package com.corebank.transaction.repository;

import com.corebank.transaction.domain.BankTransaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    Optional<BankTransaction> findByReference(String reference);

    /** Backs {@code OutboxBackfillService}'s transaction replay: a transaction never changes
     *  after posting, so postedAt alone identifies every transaction a replay window should cover. */
    List<BankTransaction> findByPostedAtBetween(Instant since, Instant until);
}
