package com.corebank.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Why an account's status is being changed")
public record StatusChangeRequest(
        @Schema(description = "Required to freeze or close; optional to unfreeze. Kept with the change.",
                example = "Customer reported the card stolen")
        @Size(max = 500) String reason) {
}
