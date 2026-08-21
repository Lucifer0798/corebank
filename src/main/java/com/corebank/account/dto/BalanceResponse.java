package com.corebank.account.dto;

import com.corebank.account.domain.Account;
import com.corebank.common.Money;
import java.math.BigDecimal;
import java.time.Instant;

@io.swagger.v3.oas.annotations.media.Schema(description = "A point-in-time balance snapshot")
public record BalanceResponse(
        String accountNumber,
        String currency,
        BigDecimal balance,
        BigDecimal availableBalance,
        Instant asOf) {

    public static BalanceResponse from(Account account) {
        return new BalanceResponse(
                account.getAccountNumber(),
                account.getCurrency(),
                Money.normalize(account.getBalance()),
                account.availableBalance(),
                Instant.now());
    }
}
