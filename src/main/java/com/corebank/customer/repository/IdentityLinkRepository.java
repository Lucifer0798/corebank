package com.corebank.customer.repository;

import com.corebank.customer.domain.IdentityLink;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Append-only, by construction: save and read, and nothing that deletes. See IdentityLinkTest. */
public interface IdentityLinkRepository extends Repository<IdentityLink, UUID> {

    IdentityLink save(IdentityLink link);

    Page<IdentityLink> findByCustomerIdOrderByLinkedAtDesc(UUID customerId, Pageable pageable);
}
