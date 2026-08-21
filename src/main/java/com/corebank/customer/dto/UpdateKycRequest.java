package com.corebank.customer.dto;

import com.corebank.customer.domain.KycStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Records the outcome of a KYC review")
public record UpdateKycRequest(@NotNull KycStatus kycStatus) {
}
