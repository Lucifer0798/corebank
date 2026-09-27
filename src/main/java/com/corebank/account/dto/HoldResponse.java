package com.corebank.account.dto;

import com.corebank.account.domain.AccountHold;
import com.corebank.account.domain.HoldStatus;
import com.corebank.common.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An authorisation hold and what became of it")
public record HoldResponse(
        UUID id,
        String reference,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String description,
        HoldStatus status,
        Instant placedAt,
        Instant expiresAt,
        Instant settledAt,

        @Schema(description = "The posting a capture produced; null on anything that moved no money")
        String capturedTransactionReference) {

    public static HoldResponse from(AccountHold hold) {
        return new HoldResponse(
                hold.getId(),
                hold.getReference(),
                hold.getAccount().getId(),
                Money.normalize(hold.getAmount()),
                hold.getCurrency(),
                hold.getDescription(),
                hold.getStatus(),
                hold.getPlacedAt(),
                hold.getExpiresAt(),
                hold.getSettledAt(),
                hold.getCapturedTransactionReference());
    }
}
