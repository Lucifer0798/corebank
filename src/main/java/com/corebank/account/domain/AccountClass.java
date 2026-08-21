package com.corebank.account.domain;

/** Separates money the bank owes a customer from the bank's own general-ledger positions. */
public enum AccountClass {
    CUSTOMER,
    INTERNAL
}
