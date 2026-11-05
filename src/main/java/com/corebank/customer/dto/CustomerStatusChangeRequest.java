package com.corebank.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record CustomerStatusChangeRequest(
        @Schema(description = "Required to suspend or close; optional to reinstate. Kept with the change.",
                example = "Fraud report under investigation")
        @Size(max = 500) String reason) {
}
