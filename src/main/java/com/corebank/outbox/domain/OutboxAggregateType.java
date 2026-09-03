package com.corebank.outbox.domain;

/** What kind of domain change an outbox row describes. Purely for observability and backfill
 *  filtering -- the relay itself treats every row identically regardless of this value. */
public enum OutboxAggregateType {
    TRANSACTION,
    CUSTOMER
}
