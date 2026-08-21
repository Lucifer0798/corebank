package com.corebank.transaction.dto;

import com.corebank.account.domain.EntryDirection;
import com.corebank.common.Money;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.domain.LedgerEntry;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A posted transaction with every ledger leg it produced")
public record TransactionResponse(
        UUID id,
        String reference,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String currency,
        String description,
        Instant postedAt,
        List<Leg> legs) {

    @Schema(description = "One side of the double-entry posting")
    public record Leg(
            UUID accountId,
            String accountNumber,
            EntryDirection direction,
            BigDecimal amount,
            BigDecimal balanceAfter) {

        static Leg from(LedgerEntry entry) {
            return new Leg(
                    entry.getAccount().getId(),
                    entry.getAccount().getAccountNumber(),
                    entry.getDirection(),
                    Money.normalize(entry.getAmount()),
                    Money.normalize(entry.getBalanceAfter()));
        }
    }

    public static TransactionResponse from(BankTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getReference(),
                transaction.getType(),
                transaction.getStatus(),
                Money.normalize(transaction.getAmount()),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getPostedAt(),
                transaction.getEntries().stream().map(Leg::from).toList());
    }
}
