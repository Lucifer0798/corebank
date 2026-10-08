package com.corebank.customer.dto;

import com.corebank.customer.domain.KycDecision;
import com.corebank.customer.domain.KycStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record KycDecisionResponse(
        UUID id,
        KycStatus fromStatus,
        KycStatus toStatus,

        @Schema(description = "The decider's token subject, or system:<process> for a non-human one")
        String decidedBySubject,

        @Schema(description = "The decider's username at the time of the decision")
        String decidedByName,

        @Schema(description = "Why. Always present on a move away from VERIFIED")
        String reason,

        Instant decidedAt) {

    public static KycDecisionResponse from(KycDecision decision) {
        return new KycDecisionResponse(
                decision.getId(),
                decision.getFromStatus(),
                decision.getToStatus(),
                decision.getDecidedBySubject(),
                decision.getDecidedByName(),
                decision.getReason(),
                decision.getDecidedAt());
    }
}
