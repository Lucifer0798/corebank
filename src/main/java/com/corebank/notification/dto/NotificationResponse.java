package com.corebank.notification.dto;

import com.corebank.account.domain.EntryDirection;
import com.corebank.common.Money;
import com.corebank.notification.domain.Notification;
import com.corebank.notification.domain.NotificationKind;
import com.corebank.transaction.domain.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Something a customer was told about money moving on one of their accounts")
public record NotificationResponse(
        UUID id,
        NotificationKind kind,
        UUID accountId,

        @Schema(description = "The posting this is about; null unless kind is TRANSACTION")
        String transactionReference,

        @Schema(description = "POSTED when the money moved, REVERSED when that movement was later undone; "
                + "null unless kind is TRANSACTION")
        TransactionStatus transactionStatus,

        @Schema(description = "DEBIT is money leaving the account, CREDIT is money arriving; "
                + "null unless kind is TRANSACTION")
        EntryDirection direction,

        @Schema(description = "The standing instruction that missed a payment; null on a TRANSACTION")
        UUID scheduledTransferId,

        @Schema(description = "The date the missed payment was due; null on a TRANSACTION")
        LocalDate dueOn,

        BigDecimal amount,

        @Schema(description = "The account's own currency, which on the receiving side of an FX transfer "
                + "is not the transaction's")
        String currency,

        @Schema(example = "500.00 INR debited from account XXXX0001")
        String message,

        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getKind(),
                notification.getAccountId(),
                notification.getTransactionReference(),
                notification.getTransactionStatus(),
                notification.getDirection(),
                notification.getScheduledTransferId(),
                notification.getDueOn(),
                Money.normalize(notification.getAmount()),
                notification.getCurrency(),
                notification.getMessage(),
                notification.getCreatedAt());
    }
}
