package com.corebank.customer.dto;

import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.CustomerStatusChange;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record CustomerStatusChangeResponse(
        UUID id,
        CustomerStatus fromStatus,
        CustomerStatus toStatus,

        @Schema(description = "The token subject of whoever made the change")
        String changedBySubject,

        @Schema(description = "Their username at the time")
        String changedByName,

        @Schema(description = "Why. Always present on a suspension or a closure")
        String reason,

        Instant changedAt) {

    public static CustomerStatusChangeResponse from(CustomerStatusChange change) {
        return new CustomerStatusChangeResponse(change.getId(), change.getFromStatus(), change.getToStatus(),
                change.getChangedBySubject(), change.getChangedByName(), change.getReason(), change.getChangedAt());
    }
}
