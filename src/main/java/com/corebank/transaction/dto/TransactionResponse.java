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

        @Schema(description = "On a REVERSAL, the reference of the posting it undoes; null otherwise",
                example = "TXN-20250417-9F3A2B1C")
        String reversalOf,

        @Schema(description = "On a cross-currency transfer, the rate applied, what the amount became "
                + "and in which currency. All three are null on a single-currency posting.")
        Fx fx,

        List<Leg> legs,

        @Schema(description = "Who made this posting: a member of staff, or a system job. Null on postings "
                + "made before this was recorded.")
        InitiatedBy initiatedBy) {

    @Schema(description = "Who made a posting")
    public record InitiatedBy(
            @Schema(description = "The token's sub claim, or system:<job>") String subject,
            @Schema(description = "The username at the time, or the job's name") String name) {

        static InitiatedBy from(BankTransaction transaction) {
            return transaction.getInitiatedBySubject() == null
                    ? null
                    : new InitiatedBy(transaction.getInitiatedBySubject(), transaction.getInitiatedByName());
        }
    }

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

    @Schema(description = "What a cross-currency posting converted, and at what rate")
    public record Fx(BigDecimal exchangeRate, BigDecimal counterAmount, String counterCurrency) {

        /** Null unless the posting actually crossed currencies -- see the V10 check constraint. */
        static Fx from(BankTransaction transaction) {
            return transaction.getExchangeRate() == null
                    ? null
                    : new Fx(transaction.getExchangeRate(),
                            Money.normalize(transaction.getCounterAmount()),
                            transaction.getCounterCurrency());
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
                transaction.getReversalOf() == null ? null : transaction.getReversalOf().getReference(),
                Fx.from(transaction),
                transaction.getEntries().stream().map(Leg::from).toList(),
                InitiatedBy.from(transaction));
    }
}
