package com.corebank.schedule.dto;

import com.corebank.common.validation.IsoCurrencyCode;
import com.corebank.common.validation.PositiveAmount;
import com.corebank.schedule.domain.ScheduleFrequency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A standing instruction to move money between two accounts on a schedule")
public record CreateScheduledTransferRequest(

        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,

        @Schema(example = "750.00")
        @PositiveAmount
        BigDecimal amount,

        @Schema(example = "INR", defaultValue = "INR")
        @IsoCurrencyCode
        String currency,

        @Schema(example = "Rent")
        @Size(max = 255) String description,

        @NotNull ScheduleFrequency frequency,

        @Schema(description = "First due date. Must not be in the past.", example = "2026-10-01")
        @NotNull LocalDate startsOn,

        @Schema(description = "Inclusive last date an occurrence may fall on; omit to run until cancelled",
                example = "2027-09-30")
        LocalDate endsOn) {
}
