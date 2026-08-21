package com.corebank.transaction.dto;

import com.corebank.account.domain.EntryDirection;
import com.corebank.common.Money;
import com.corebank.transaction.domain.LedgerEntry;
import com.corebank.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One line of an account statement, seen from that account's side")
public record StatementLineResponse(
        UUID entryId,
        String reference,
        TransactionType type,
        EntryDirection direction,
        @Schema(description = "Negative when the entry reduced this account's balance")
        BigDecimal signedAmount,
        BigDecimal balanceAfter,
        String description,
        Instant postedAt) {

    public static StatementLineResponse from(LedgerEntry entry) {
        return new StatementLineResponse(
                entry.getId(),
                entry.getTransaction().getReference(),
                entry.getTransaction().getType(),
                entry.getDirection(),
                Money.normalize(entry.signedAmount()),
                Money.normalize(entry.getBalanceAfter()),
                entry.getTransaction().getDescription(),
                entry.getPostedAt());
    }
}
