package com.corebank.transaction.dto;

import com.corebank.common.validation.IsoCurrencyCode;
import com.corebank.common.validation.PositiveAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Moves money between two accounts held at this bank")
public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,

        @Schema(example = "750.00")
        @PositiveAmount
        BigDecimal amount,

        @Schema(example = "INR", defaultValue = "INR")
        @IsoCurrencyCode
        String currency,

        @Schema(example = "Rent for August")
        @Size(max = 255) String description) {
}
