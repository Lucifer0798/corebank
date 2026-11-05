package com.corebank.customer.repository;

import com.corebank.customer.domain.CustomerStatusChange;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Append-only, by construction: a bare {@link Repository} with nothing but save and read, so no
 * delete exists to call. {@code CustomerLifecycleTest} fails if one is ever declared here.
 */
public interface CustomerStatusChangeRepository extends Repository<CustomerStatusChange, UUID> {

    CustomerStatusChange save(CustomerStatusChange change);

    Page<CustomerStatusChange> findByCustomerIdOrderByChangedAtDescSeqDesc(UUID customerId, Pageable pageable);
}
