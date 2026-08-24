package com.corebank.customer.service;

import com.corebank.common.SequenceNumberGenerator;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ConflictException;
import com.corebank.common.exception.ResourceNotFoundException;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.dto.CustomerResponse;
import com.corebank.customer.repository.CustomerRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    static final String CUSTOMER_NUMBER_SEQUENCE = "customer_number_seq";
    static final int MINIMUM_AGE_YEARS = 18;

    private final CustomerRepository customers;
    private final SequenceNumberGenerator sequences;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, SequenceNumberGenerator sequences, Clock clock) {
        this.customers = customers;
        this.sequences = sequences;
        this.clock = clock;
    }

    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        String email = request.email().trim();
        if (customers.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("EMAIL_TAKEN", "A customer with email '" + email + "' already exists");
        }
        assertOldEnough(request.dateOfBirth());

        Customer customer = new Customer();
        customer.setCustomerNumber("CUST%08d".formatted(sequences.next(CUSTOMER_NUMBER_SEQUENCE)));
        customer.setFirstName(request.firstName().trim());
        customer.setLastName(request.lastName().trim());
        customer.setEmail(email);
        customer.setPhone(request.phone());
        customer.setDateOfBirth(request.dateOfBirth());
        customer.setKycStatus(KycStatus.PENDING);
        customer.setStatus(CustomerStatus.ACTIVE);

        return CustomerResponse.from(customers.save(customer));
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) {
        return CustomerResponse.from(require(id));
    }

    /** Resolves the customer linked to a CUSTOMER-role token's own subject, for self-service. */
    @Transactional(readOnly = true)
    public CustomerResponse getBySubject(String keycloakSubject) {
        return customers.findByKeycloakSubject(keycloakSubject)
                .map(CustomerResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "linked to this identity"));
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(Pageable pageable) {
        return customers.findAll(pageable).map(CustomerResponse::from);
    }

    @Transactional
    public CustomerResponse updateKyc(UUID id, KycStatus kycStatus) {
        Customer customer = require(id);
        if (customer.getStatus() == CustomerStatus.CLOSED) {
            throw new BusinessRuleException("CUSTOMER_CLOSED", "A closed customer cannot be re-reviewed");
        }
        customer.setKycStatus(kycStatus);
        return CustomerResponse.from(customer);
    }

    /**
     * Links a Keycloak identity to this customer, so that identity's tokens can read the
     * customer's own accounts. One identity may only ever be linked to one customer; the
     * database's unique constraint is the real guard, this check exists to fail with a clear
     * message rather than a bare constraint-violation one.
     */
    @Transactional
    public CustomerResponse linkIdentity(UUID id, String keycloakSubject) {
        Customer customer = require(id);
        if (customers.existsByKeycloakSubject(keycloakSubject)) {
            throw new ConflictException("IDENTITY_ALREADY_LINKED",
                    "This Keycloak identity is already linked to another customer");
        }
        customer.setKeycloakSubject(keycloakSubject);
        return CustomerResponse.from(customer);
    }

    /** Loads a customer for other services, raising the standard 404 when absent. */
    @Transactional(readOnly = true)
    public Customer require(UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    private void assertOldEnough(LocalDate dateOfBirth) {
        int age = Period.between(dateOfBirth, LocalDate.now(clock)).getYears();
        if (age < MINIMUM_AGE_YEARS) {
            throw new BusinessRuleException("UNDERAGE_CUSTOMER",
                    "A customer must be at least " + MINIMUM_AGE_YEARS + " years old");
        }
    }
}
