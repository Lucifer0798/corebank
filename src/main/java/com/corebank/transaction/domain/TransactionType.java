package com.corebank.transaction.domain;

public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    /**
     * The undoing of an earlier posting, as its own transaction with its own reference.
     * A reversal is never itself reversible -- correcting a mistaken reversal means posting
     * the original movement again, not stacking a second correction on top of the first.
     */
    REVERSAL
}
