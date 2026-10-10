package com.corebank.account.repository;

import com.corebank.account.domain.AccountStatusChange;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Append-only, by construction: a bare {@link Repository} with nothing but save and read, so no
 * delete exists to call. {@code AccountStatusHistoryTest} fails if one is ever declared here.
 */
public interface AccountStatusChangeRepository extends Repository<AccountStatusChange, UUID> {

    AccountStatusChange save(AccountStatusChange change);

    Page<AccountStatusChange> findByAccountIdOrderByChangedAtDesc(UUID accountId, Pageable pageable);
}
