package com.corebank.customer.dto;

import com.corebank.customer.domain.IdentityLink;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record IdentityLinkResponse(
        UUID id,

        @Schema(description = "The login this replaced; null on a first link")
        String previousSubject,

        String newSubject,
        String linkedBySubject,
        String linkedByName,

        @Schema(description = "Why. Always present on a relink")
        String reason,

        Instant linkedAt) {

    public static IdentityLinkResponse from(IdentityLink link) {
        return new IdentityLinkResponse(link.getId(), link.getPreviousSubject(), link.getNewSubject(),
                link.getLinkedBySubject(), link.getLinkedByName(), link.getReason(), link.getLinkedAt());
    }
}
