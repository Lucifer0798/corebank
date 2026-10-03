package com.corebank.transaction.messaging;

/**
 * A posting has been reversed. Published in-process, inside the reversal's own transaction, so
 * anything holding a record that points at the original can bring it into line atomically with the
 * reversal -- and roll back with it if the reversal fails.
 *
 * <p>An event rather than a call, so that the transaction side never has to know what else refers
 * to a posting. Today that is a hold whose capture produced it; the next derived record should not
 * need an edit to {@code TransactionService} to stay truthful.
 *
 * <p>Not sent to Kafka. Downstream consumers learn of a reversal from the original being
 * re-published with status REVERSED, which they already know how to read.
 */
public record TransactionReversedEvent(String originalReference, String reversalReference) {
}
