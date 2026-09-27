package com.corebank.transaction.service;

import com.corebank.common.exception.LimitExceededException;
import com.corebank.config.CoreBankProperties;
import com.corebank.transaction.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * How much may leave one account, and how fast.
 *
 * <p>Two separate refusals, checked in this order:
 *
 * <ul>
 *   <li>the <strong>single-posting</strong> ceiling, which catches the fat-fingered transfer -- an
 *       extra zero is a different mistake from a busy day, and telling someone they have exhausted
 *       a daily allowance when they actually typed 500000 instead of 50000 sends them looking in
 *       the wrong place;
 *   <li>the <strong>daily</strong> total, measured over a UTC calendar day.
 * </ul>
 *
 * <p>The day's total is summed from the ledger on each check rather than held in a counter. That
 * is the opposite choice from {@code account.held_amount}, and deliberately: a counter here would
 * need decrementing whenever a withdrawal was reversed, and a bug in that path costs a customer
 * allowance for a posting the bank itself undid. Reading the entries makes reversal handling fall
 * out of the query instead -- see {@link LedgerEntryRepository#sumDebitsBetween}. The read is a
 * narrow indexed range scan over one account and one day, inside a transaction that already holds
 * that account's row lock, so nothing can slip in between the check and the posting.
 *
 * <p>Counted against the limit: withdrawals and outgoing transfers. Not counted, and not checked:
 * reversals (a correction the bank owes must never be refused) and hold captures (the
 * authorisation was checked when the hold was placed, and refusing the capture would undo the
 * guarantee that made the hold worth anything).
 */
@Component
public class VelocityLimits {

    private final LedgerEntryRepository entries;
    private final CoreBankProperties.Limits limits;
    private final Clock clock;

    public VelocityLimits(LedgerEntryRepository entries, CoreBankProperties properties, Clock clock) {
        this.entries = entries;
        this.limits = properties.limits();
        this.clock = clock;
    }

    /** Refuses the posting if it would breach either control. */
    public void assertWithin(UUID accountId, BigDecimal amount) {
        assertWithin(accountId, amount, BigDecimal.ZERO);
    }

    /**
     * As above, with {@code pendingToday} counted against the daily total alongside the postings
     * that have actually settled.
     *
     * <p>This exists for authorisation holds. A capture is exempt from the limit -- refusing one
     * would undo the guarantee a hold is for -- so without counting outstanding holds at the point
     * they are placed, holds would be a complete bypass: authorise any amount, capture it, and the
     * daily ceiling never applies. Counting them here puts the check where a card network puts it,
     * at authorisation.
     *
     * <p>One case remains deliberately unhandled: a hold placed on one day and captured on the next
     * consumes the capture day's allowance without having been checked against it. Closing it would
     * mean either refusing captures or reserving allowance across days, and both are worse than the
     * overshoot -- the first breaks the guarantee, the second denies a customer money they have not
     * spent.
     */
    public void assertWithin(UUID accountId, BigDecimal amount, BigDecimal pendingToday) {
        if (amount.compareTo(limits.singleTransactionLimit()) > 0) {
            throw LimitExceededException.singleTransaction(amount, limits.singleTransactionLimit());
        }

        BigDecimal committed = debitedToday(accountId).add(pendingToday);
        if (committed.add(amount).compareTo(limits.dailyDebitLimit()) > 0) {
            throw LimitExceededException.daily(committed, amount, limits.dailyDebitLimit());
        }
    }

    /** The UTC calendar day the daily total is measured over, as a half-open instant range. */
    public Instant[] todayBounds() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return new Instant[] {
                today.atStartOfDay(ZoneOffset.UTC).toInstant(),
                today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        };
    }

    /** What this account has had debited so far today, net of anything reversed. */
    public BigDecimal debitedToday(UUID accountId) {
        Instant[] bounds = todayBounds();
        return entries.sumDebitsBetween(accountId, bounds[0], bounds[1]);
    }

    public BigDecimal dailyLimit() {
        return limits.dailyDebitLimit();
    }
}
