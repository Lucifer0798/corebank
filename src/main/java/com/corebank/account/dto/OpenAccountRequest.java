package com.corebank.account.dto;

import com.corebank.account.domain.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Opens a new account for an existing, KYC-verified customer")
public record OpenAccountRequest(
        @NotNull UUID customerId,

        @Schema(example = "SAVINGS", description = "SAVINGS or CURRENT. General-ledger types cannot be opened through the API.")
        @NotNull AccountType accountType,

        @Schema(example = "INR", defaultValue = "INR")
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO 4217 code")
        String currency,

        @Schema(example = "0.00", description = "Agreed overdraft. Only a CURRENT account may have one.")
        @DecimalMin(value = "0.00", message = "cannot be negative")
        @Digits(integer = 15, fraction = 2, message = "supports at most two decimal places")
        BigDecimal overdraftLimit) {
}
