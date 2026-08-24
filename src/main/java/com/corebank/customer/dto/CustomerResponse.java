package com.corebank.customer.dto;

import com.corebank.customer.domain.Customer;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String customerNumber,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDate dateOfBirth,
        KycStatus kycStatus,
        CustomerStatus status,
        boolean identityLinked,
        Instant createdAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getCustomerNumber(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getDateOfBirth(),
                customer.getKycStatus(),
                customer.getStatus(),
                customer.getKeycloakSubject() != null,
                customer.getCreatedAt());
    }
}
