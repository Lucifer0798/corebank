package com.corebank.account.repository;

import com.corebank.account.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    Page<Account> findByCustomerId(UUID customerId, Pageable pageable);

    List<Account> findByCustomerId(UUID customerId);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * Takes a row lock for the duration of a posting. Every money-moving path loads its
     * accounts through this method, so two concurrent postings on the same account serialise
     * at the database instead of racing on a stale in-memory balance.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);

    @Query("select count(a) from Account a where a.customer.id = :customerId and a.status <> com.corebank.account.domain.AccountStatus.CLOSED")
    long countOpenAccounts(@Param("customerId") UUID customerId);
}
