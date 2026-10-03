package com.corebank.transaction.messaging;

import com.corebank.account.domain.EntryDirection;
import com.corebank.common.Money;
import com.corebank.transaction.domain.BankTransaction;
import com.corebank.transaction.domain.LedgerEntry;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The wire contract published to Kafka once a transaction commits. Deliberately its own type
 * rather than a reuse of {@code TransactionResponse}: the HTTP DTO is free to change shape for
 * API reasons without silently changing what downstream consumers of this topic receive.
 */
public record TransactionPostedEvent(
        String reference,
        TransactionType type,
        /*
         * Added after the contract was in use, so a consumer reading an older message gets null here.
         * Null means POSTED: no status was ever sent before reversals propagated, and every message of
         * that era described a transaction at the moment it was posted. See statusOrPosted().
         */
        TransactionStatus status,
        BigDecimal amount,
        String currency,
        String description,
        Instant postedAt,
        List<Leg> legs) {

    public record Leg(String accountNumber, EntryDirection direction, BigDecimal amount, BigDecimal balanceAfter) {

        static Leg from(LedgerEntry entry) {
            return new Leg(
                    entry.getAccount().getAccountNumber(),
                    entry.getDirection(),
                    Money.normalize(entry.getAmount()),
                    Money.normalize(entry.getBalanceAfter()));
        }
    }

    /** The status, reading a message from before the field existed as the posting it was. */
    public TransactionStatus statusOrPosted() {
        return status == null ? TransactionStatus.POSTED : status;
    }

    public static TransactionPostedEvent from(BankTransaction transaction) {
        return new TransactionPostedEvent(
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
