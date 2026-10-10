package com.corebank.account.dto;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountStatusChange;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record AccountStatusChangeResponse(
        UUID id,
        AccountStatus fromStatus,
        AccountStatus toStatus,

        @Schema(description = "The token subject of whoever made the change")
        String changedBySubject,

        @Schema(description = "Their username at the time")
        String changedByName,

        @Schema(description = "Why. Always present on a freeze or a closure")
        String reason,

        Instant changedAt) {

    public static AccountStatusChangeResponse from(AccountStatusChange change) {
        return new AccountStatusChangeResponse(change.getId(), change.getFromStatus(), change.getToStatus(),
                change.getChangedBySubject(), change.getChangedByName(), change.getReason(), change.getChangedAt());
    }
}
