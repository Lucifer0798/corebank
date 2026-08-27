package com.corebank.search.dto;

import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}: without it, deserializing a hit throws
 * {@code UnrecognizedPropertyException} on the index document's {@code changedAt} field, which
 * this DTO deliberately doesn't expose -- confirmed against a real search request, not caught
 * until the index actually held a document with a field this record omits.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerSearchHit(
        String id,
        String customerNumber,
        String firstName,
        String lastName,
        String email,
        String phone,
        KycStatus kycStatus,
        CustomerStatus status) {
}
