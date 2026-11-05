package com.corebank.customer.repository;

import com.corebank.customer.domain.Customer;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByCustomerNumber(String customerNumber);

    Optional<Customer> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByKeycloakSubject(String keycloakSubject);

    Optional<Customer> findByKeycloakSubject(String keycloakSubject);

    /**
     * Takes the customer's row lock. Changing a customer's status and opening an account both do,
     * so closing a customer cannot slip between another request's "still active" and its new account.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") UUID id);

    /** Backs {@code OutboxBackfillService}'s customer replay: updatedAt, not createdAt, since a
     *  KYC or identity change -- not just creation -- is exactly the kind of change that needs
     *  re-publishing after a gap. Paged rather than loaded whole -- see the equivalent transaction
     *  repository method for why. */
    Slice<Customer> findByUpdatedAtBetween(Instant since, Instant until, Pageable pageable);
}
