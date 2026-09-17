package com.corebank.account.dto;

import com.corebank.account.domain.AccountType;
import com.corebank.common.validation.IsoCurrencyCode;
import com.corebank.common.validation.MoneyDigits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Opens a new account for an existing, KYC-verified customer")
public record OpenAccountRequest(
        @NotNull UUID customerId,

        @Schema(example = "SAVINGS", description = "SAVINGS or CURRENT. General-ledger types cannot be opened through the API.")
        @NotNull AccountType accountType,

        @Schema(example = "INR", defaultValue = "INR")
        @IsoCurrencyCode
        String currency,

        @Schema(example = "0.00", description = "Agreed overdraft. Only a CURRENT account may have one.")
        @DecimalMin(value = "0.00", message = "cannot be negative")
        @MoneyDigits
        BigDecimal overdraftLimit) {
}
