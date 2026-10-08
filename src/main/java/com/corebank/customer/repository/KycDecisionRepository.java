package com.corebank.customer.repository;

import com.corebank.customer.domain.KycDecision;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Append-only, by construction. This extends {@link Repository} rather than {@code JpaRepository}
 * so that no delete method exists to call: the history can be added to and read, and nothing
 * else. {@code KycDecisionHistoryTest} fails if a delete or update method is ever declared here.
 */
public interface KycDecisionRepository extends Repository<KycDecision, UUID> {

    KycDecision save(KycDecision decision);

    Page<KycDecision> findByCustomerIdOrderByDecidedAtDesc(UUID customerId, Pageable pageable);
}
