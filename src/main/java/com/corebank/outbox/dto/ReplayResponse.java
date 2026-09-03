package com.corebank.outbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReplayResponse(
        @Schema(description = "How many outbox rows this call wrote") int eventsEnqueued) {
}
