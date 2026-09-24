package com.corebank.schedule.dto;

import com.corebank.common.Money;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.domain.ScheduleStatus;
import com.corebank.schedule.domain.ScheduledTransfer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A standing instruction, and how it has fared so far")
public record ScheduledTransferResponse(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String description,
        ScheduleFrequency frequency,
        LocalDate startsOn,
        LocalDate endsOn,
        ScheduleStatus status,

        @Schema(description = "When the next occurrence falls due; null once nothing further is due")
        LocalDate nextRunOn,

        int runsCompleted,

        @Schema(description = "Failures since the last success. Enough of them in a row suspend the schedule.")
        int consecutiveFailures,

        LocalDate lastRunOn,

        @Schema(description = "Why the most recent attempt was refused, if it was")
        String lastError) {

    public static ScheduledTransferResponse from(ScheduledTransfer schedule) {
        return new ScheduledTransferResponse(
                schedule.getId(),
                schedule.getSourceAccountId(),
                schedule.getDestinationAccountId(),
                Money.normalize(schedule.getAmount()),
                schedule.getCurrency(),
                schedule.getDescription(),
                schedule.getFrequency(),
                schedule.getStartsOn(),
                schedule.getEndsOn(),
                schedule.getStatus(),
                schedule.getNextRunOn(),
                schedule.getRunsCompleted(),
                schedule.getConsecutiveFailures(),
                schedule.getLastRunOn(),
                schedule.getLastError());
    }
}
