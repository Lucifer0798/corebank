package com.corebank.account.domain;

public enum AccountType {
    SAVINGS,
    CURRENT,
    CASH_GL,
    SUSPENSE_GL,
    /** The bank's own cost of paying interest -- the contra side of every capitalisation. */
    INTEREST_EXPENSE_GL,
    /**
     * The bank's position in one currency. Every cross-currency transfer passes through two of
     * these -- credited in the currency received, debited in the currency paid out -- so that each
     * side of the trade balances in its own money.
     */
    FX_POSITION_GL
}
