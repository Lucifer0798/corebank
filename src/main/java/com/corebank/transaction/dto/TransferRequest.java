package com.corebank.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Moves money between two accounts held at this bank")
public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,

        @Schema(example = "750.00")
        @NotNull
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @Digits(integer = 15, fraction = 2, message = "supports at most two decimal places")
        BigDecimal amount,

        @Schema(example = "INR", defaultValue = "INR")
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO 4217 code")
        String currency,

        @Schema(example = "Rent for August")
        @Size(max = 255) String description) {
}
