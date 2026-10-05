package com.corebank.notification.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One standing-instruction occurrence that did not pay, as the notification side needs to hear
 * about it. Plain values rather than the schedule entity, so this package does not depend on the
 * schedule package that calls it.
 *
 * @param nextRunOn the next occurrence, when {@code outcome} is {@link Outcome#RETRYING}; else null
 * @param consecutiveFailures failures in a row including this one
 */
public record MissedScheduledTransfer(
        UUID scheduledTransferId,
        UUID sourceAccountId,
        LocalDate dueOn,
        BigDecimal amount,
        String currency,
        String failureCode,
        Outcome outcome,
        LocalDate nextRunOn,
        int consecutiveFailures) {

    public enum Outcome {
        /** The instruction carries on; the next occurrence is still due. */
        RETRYING,
        /** Failed too many times running, and has been stopped. */
        STOPPED,
        /** That was its last occurrence, so there is nothing further to try. */
        ENDED
    }
}
