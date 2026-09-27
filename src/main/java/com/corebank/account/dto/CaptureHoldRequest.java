package com.corebank.account.dto;

import com.corebank.common.validation.MoneyDigits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

@Schema(description = "Claims a hold, in full or in part")
public record CaptureHoldRequest(

        // Deliberately not @PositiveAmount: that composes @NotNull, and omitting the amount is the
        // documented way to capture the hold in full. The other two halves of it still apply.
        @Schema(description = "Omit to capture the full held amount. Less is the ordinary case -- a "
                + "pre-authorisation is an upper bound, and the final bill is usually lower. More is "
                + "allowed (a tip added after the fact), but only the held portion is guaranteed: the "
                + "excess is checked against the available balance like any other withdrawal.",
                example = "3800.00")
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @MoneyDigits
        BigDecimal amount) {
}
