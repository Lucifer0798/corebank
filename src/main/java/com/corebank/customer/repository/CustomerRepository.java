package com.corebank.customer.repository;

import com.corebank.customer.domain.Customer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByCustomerNumber(String customerNumber);

    Optional<Customer> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByKeycloakSubject(String keycloakSubject);

    Optional<Customer> findByKeycloakSubject(String keycloakSubject);
}
