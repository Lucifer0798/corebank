package com.corebank.customer.dto;

import com.corebank.customer.domain.KycStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Records the outcome of a KYC review")
public record UpdateKycRequest(
        @NotNull KycStatus kycStatus,

        @Schema(description = "Why. Required for anything but VERIFIED -- the customer can no longer send "
                + "money, and the reason is kept with the decision.",
                example = "Address could not be confirmed on re-check")
        @Size(max = 500) String reason) {
}
