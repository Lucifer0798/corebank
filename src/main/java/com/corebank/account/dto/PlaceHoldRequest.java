package com.corebank.account.dto;

import com.corebank.common.validation.IsoCurrencyCode;
import com.corebank.common.validation.PositiveAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "Reserves money against an account without moving it")
public record PlaceHoldRequest(

        @Schema(example = "4500.00")
        @PositiveAmount
        BigDecimal amount,

        @Schema(example = "INR", defaultValue = "INR")
        @IsoCurrencyCode
        String currency,

        @Schema(example = "Hotel authorisation, Taj Bengaluru")
        @Size(max = 255) String description,

        @Schema(description = "How long the reservation lasts. Defaults to 7 days, the usual card-scheme window.",
                example = "168")
        @Min(1) @Max(720) Integer expiresInHours) {
}
