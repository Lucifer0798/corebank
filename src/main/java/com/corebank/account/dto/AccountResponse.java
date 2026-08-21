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
                Money.normalize(account.getOverdraftLimit()),
                account.getStatus(),
                account.getOpenedAt(),
                account.getClosedAt());
    }
}
