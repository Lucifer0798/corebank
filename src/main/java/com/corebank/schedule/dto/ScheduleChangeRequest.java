package com.corebank.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Why a standing instruction is being cancelled or resumed")
public record ScheduleChangeRequest(
        @Schema(description = "Optional: cancelling or resuming is usually routine, on the customer's own request",
                example = "Customer moved to a new landlord")
        @Size(max = 500) String reason) {
}
