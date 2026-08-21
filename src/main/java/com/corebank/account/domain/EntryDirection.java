package com.corebank.account.domain;

/**
 * The two sides of a double-entry posting. Whether a direction raises or lowers an
 * account's balance depends on that account's normal balance, not on the direction alone.
 */
public enum EntryDirection {
    DEBIT,
    CREDIT;

    public EntryDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
