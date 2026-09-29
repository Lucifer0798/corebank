package com.corebank.account.domain;

public enum AccountType {
    SAVINGS,
    CURRENT,
    CASH_GL,
    SUSPENSE_GL,
    /** The bank's own cost of paying interest -- the contra side of every capitalisation. */
    INTEREST_EXPENSE_GL
}
