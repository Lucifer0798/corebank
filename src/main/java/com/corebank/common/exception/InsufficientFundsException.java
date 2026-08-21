package com.corebank.common.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends BusinessRuleException {

    public InsufficientFundsException(String accountNumber, BigDecimal available, BigDecimal requested) {
        super("INSUFFICIENT_FUNDS",
                "Account " + accountNumber + " has " + available + " available but " + requested + " was requested");
    }
}
