package com.corebank.transaction.dto;

import com.corebank.common.validation.IsoCurrencyCode;
import com.corebank.common.validation.PositiveAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "A deposit into, or a withdrawal from, one account")
public record AmountRequest(
        @Schema(example = "2500.00")
        @PositiveAmount
        BigDecimal amount,

        @Schema(example = "INR", defaultValue = "INR")
        @IsoCurrencyCode
        String currency,

        @Schema(example = "Branch counter deposit")
        @Size(max = 255) String description) {
}
