package com.corebank.account.repository;

import com.corebank.account.domain.Account;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
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

    /**
     * Savings accounts that have not yet been accrued for {@code day}.
     *
     * <p>Ids only, and no lock: this is the run deciding what to look at, and each account is then
     * re-read and locked in its own transaction so one failure cannot roll back the batch -- the
     * same shape the scheduled-transfer and hold runners use.
     *
     * <p>{@code interestAccruedThrough IS NULL} catches accounts opened since the last run, which
     * would otherwise never be picked up at all.
     */
    @Query("""
            select a.id from Account a
             where a.accountType = com.corebank.account.domain.AccountType.SAVINGS
               and a.status = com.corebank.account.domain.AccountStatus.ACTIVE
               and (a.interestAccruedThrough is null or a.interestAccruedThrough < :day)
             order by a.id asc
            """)
    List<UUID> findSavingsNotAccruedThrough(@Param("day") LocalDate day, Pageable pageable);

    /**
     * Savings accounts holding accrued interest worth paying out. The threshold is one paisa
     * rather than zero, because anything below it rounds down to nothing and capitalising it
     * would write a zero-amount posting the ledger refuses.
     */
    @Query("""
            select a.id from Account a
             where a.accountType = com.corebank.account.domain.AccountType.SAVINGS
               and a.accruedInterest >= 0.01
             order by a.id asc
            """)
    List<UUID> findSavingsWithAccruedInterest(Pageable pageable);
}
