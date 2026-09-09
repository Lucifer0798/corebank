package com.corebank.account.dto;

import java.math.BigDecimal;
import java.time.Instant;

@io.swagger.v3.oas.annotations.media.Schema(description = "A point-in-time balance snapshot")
public record BalanceResponse(
        String accountNumber,
        String currency,
        BigDecimal balance,
        BigDecimal availableBalance,
        Instant asOf) {

    public static BalanceResponse from(AccountResponse account) {
        return new BalanceResponse(
                account.accountNumber(),
                account.currency(),
                account.balance(),
                account.availableBalance(),
                Instant.now());
    }
}
