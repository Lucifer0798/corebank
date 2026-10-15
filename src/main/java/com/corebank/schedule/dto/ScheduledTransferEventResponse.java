package com.corebank.schedule.dto;

import com.corebank.common.security.Actor;
import com.corebank.schedule.domain.ScheduleEventType;
import com.corebank.schedule.domain.ScheduledTransferEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record ScheduledTransferEventResponse(
        UUID id,
        ScheduleEventType event,

        @Schema(description = "Who did it: a member of staff, or system:scheduled-transfer-runner for a suspension")
        Actor by,

        String reason,
        Instant occurredAt) {

    public static ScheduledTransferEventResponse from(ScheduledTransferEvent event) {
        return new ScheduledTransferEventResponse(event.getId(), event.getEvent(),
                new Actor(event.getActorSubject(), event.getActorName()), event.getReason(), event.getOccurredAt());
    }
}
