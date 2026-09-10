package com.corebank.customer.repository;

import com.corebank.customer.domain.Customer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByCustomerNumber(String customerNumber);

    Optional<Customer> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByKeycloakSubject(String keycloakSubject);

    Optional<Customer> findByKeycloakSubject(String keycloakSubject);

    /** Backs {@code OutboxBackfillService}'s customer replay: updatedAt, not createdAt, since a
     *  KYC or identity change -- not just creation -- is exactly the kind of change that needs
     *  re-publishing after a gap. Paged rather than loaded whole -- see the equivalent transaction
     *  repository method for why. */
    Slice<Customer> findByUpdatedAtBetween(Instant since, Instant until, Pageable pageable);
}
