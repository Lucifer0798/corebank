package com.corebank.transaction.repository;

import com.corebank.transaction.domain.BankTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    Optional<BankTransaction> findByReference(String reference);
}
