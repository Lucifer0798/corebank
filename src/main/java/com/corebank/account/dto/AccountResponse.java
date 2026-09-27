package com.corebank.account.dto;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.common.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        UUID customerId,
        AccountType accountType,
        String currency,
        BigDecimal balance,
        BigDecimal availableBalance,

        @io.swagger.v3.oas.annotations.media.Schema(
                description = "Reserved by outstanding authorisation holds, and already subtracted "
                        + "from availableBalance. Shown separately so the gap between the two figures "
                        + "has a visible reason.")
        BigDecimal heldAmount,

        BigDecimal overdraftLimit,
        AccountStatus status,
        Instant openedAt,
        Instant closedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomer() == null ? null : account.getCustomer().getId(),
                account.getAccountType(),
                account.getCurrency(),
                Money.normalize(account.getBalance()),
                account.availableBalance(),
                Money.normalize(account.getHeldAmount()),
                Money.normalize(account.getOverdraftLimit()),
                account.getStatus(),
                account.getOpenedAt(),
                account.getClosedAt());
    }
}
