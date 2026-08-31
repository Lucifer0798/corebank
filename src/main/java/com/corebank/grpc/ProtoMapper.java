package com.corebank.grpc;

import com.corebank.account.dto.AccountResponse;
import com.corebank.grpc.proto.Account;
import com.corebank.grpc.proto.StatementLine;
import com.corebank.grpc.proto.Transaction;
import com.corebank.grpc.proto.TransactionLeg;
import com.corebank.transaction.dto.StatementLineResponse;
import com.corebank.transaction.dto.TransactionResponse;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Turns the same DTOs the REST controllers return into their proto equivalents, so both surfaces
 * are views of one service layer rather than two parallel implementations.
 *
 * <p>proto3 has no null: an unset string field reads back as {@code ""}, not null. Every optional
 * value is therefore mapped to an empty string deliberately -- a caller distinguishes "no closing
 * date" by the empty string, the same way the JSON API uses {@code null}. Amounts go over as
 * their exact {@code BigDecimal.toPlainString()}, never as a double; see the note in
 * corebank.proto.
 */
final class ProtoMapper {

    private ProtoMapper() {
    }

    static Account toProto(AccountResponse account) {
        return Account.newBuilder()
                .setId(text(account.id()))
                .setAccountNumber(text(account.accountNumber()))
                .setCustomerId(text(account.customerId()))
                .setAccountType(text(account.accountType()))
                .setCurrency(text(account.currency()))
                .setBalance(amount(account.balance()))
                .setAvailableBalance(amount(account.availableBalance()))
                .setOverdraftLimit(amount(account.overdraftLimit()))
                .setStatus(text(account.status()))
                .setOpenedAt(timestamp(account.openedAt()))
                .setClosedAt(timestamp(account.closedAt()))
                .build();
    }

    static Transaction toProto(TransactionResponse transaction) {
        Transaction.Builder builder = Transaction.newBuilder()
                .setId(text(transaction.id()))
                .setReference(text(transaction.reference()))
                .setType(text(transaction.type()))
                .setStatus(text(transaction.status()))
                .setAmount(amount(transaction.amount()))
                .setCurrency(text(transaction.currency()))
                .setDescription(text(transaction.description()))
                .setPostedAt(timestamp(transaction.postedAt()));
        for (TransactionResponse.Leg leg : transaction.legs()) {
            builder.addLegs(TransactionLeg.newBuilder()
                    .setAccountId(text(leg.accountId()))
                    .setAccountNumber(text(leg.accountNumber()))
                    .setDirection(text(leg.direction()))
                    .setAmount(amount(leg.amount()))
                    .setBalanceAfter(amount(leg.balanceAfter()))
                    .build());
        }
        return builder.build();
    }

    static StatementLine toProto(StatementLineResponse line) {
        return StatementLine.newBuilder()
                .setEntryId(text(line.entryId()))
                .setReference(text(line.reference()))
                .setType(text(line.type()))
                .setDirection(text(line.direction()))
                .setSignedAmount(amount(line.signedAmount()))
                .setBalanceAfter(amount(line.balanceAfter()))
                .setDescription(text(line.description()))
                .setPostedAt(timestamp(line.postedAt()))
                .build();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String amount(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String timestamp(Instant value) {
        return value == null ? "" : value.toString();
    }
}
