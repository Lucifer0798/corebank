package com.corebank.customer.messaging;

import com.corebank.customer.domain.Customer;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The wire contract published to Kafka whenever a customer is created or its KYC/identity state
 * changes. Deliberately its own type rather than a reuse of {@code CustomerResponse}, for the
 * same reason {@code TransactionPostedEvent} isn't {@code TransactionResponse}: the HTTP DTO is
 * free to change shape for API reasons without silently changing what downstream consumers of
 * this topic -- today, only the OpenSearch indexer -- receive.
 */
public record CustomerChangedEvent(
        UUID id,
        String customerNumber,
        String firstName,
        String lastName,
        String email,
        String phone,
        KycStatus kycStatus,
        CustomerStatus status,
        Instant changedAt) {

    public static CustomerChangedEvent from(Customer customer) {
        return new CustomerChangedEvent(
                customer.getId(),
                customer.getCustomerNumber(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getKycStatus(),
                customer.getStatus(),
                Instant.now());
    }
}
