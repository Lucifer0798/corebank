package com.corebank.transaction.service;

import com.corebank.common.exception.LimitExceededException;
import com.corebank.common.Money;
import com.corebank.config.CoreBankProperties;
import com.corebank.fx.service.FxRateService;
import com.corebank.transaction.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * The currency the limits are configured in. The base currency, so a deployment's existing
     * {@code COREBANK_DAILY_DEBIT_LIMIT} keeps meaning what it always meant for rupee accounts.
     */
    static final String LIMIT_CURRENCY = Money.BASE_CURRENCY;

    private final LedgerEntryRepository entries;
    private final CoreBankProperties.Limits limits;
    private final FxRateService fxRateService;
    private final Clock clock;

    public VelocityLimits(LedgerEntryRepository entries, CoreBankProperties properties,
                          FxRateService fxRateService, Clock clock) {
        this.entries = entries;
        this.limits = properties.limits();
        this.fxRateService = fxRateService;
        this.clock = clock;
    }

    /**
     * Refuses the posting if it would breach either control.
     *
     * <p>{@code currency} is the account's, and it is required. Without it this compared a dollar
     * amount against a limit configured in rupees as though they were the same unit, so a dollar
     * account got roughly 83 times the allowance of a rupee one -- the limits were written when every
     * account was in rupees, and #36 made the others real.
     */
    public void assertWithin(UUID accountId, String currency, BigDecimal amount) {
        assertWithin(accountId, currency, amount, BigDecimal.ZERO);
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
    public void assertWithin(UUID accountId, String currency, BigDecimal amount, BigDecimal pendingToday) {
        // Everything is converted into the limits' currency before it is compared. The day's total
        // is summed in the account's own currency -- every entry on one account is in that currency
        // -- so it converts at the same single rate as the amount.
        BigDecimal rate = rateToLimitCurrency(currency);
        BigDecimal requested = toLimitCurrency(amount, rate);

        if (requested.compareTo(limits.singleTransactionLimit()) > 0) {
            throw LimitExceededException.singleTransaction(
                    requested, limits.singleTransactionLimit(), LIMIT_CURRENCY);
        }

        BigDecimal committed = toLimitCurrency(debitedToday(accountId).add(pendingToday), rate);
        if (committed.add(requested).compareTo(limits.dailyDebitLimit()) > 0) {
            throw LimitExceededException.daily(
                    committed, requested, limits.dailyDebitLimit(), LIMIT_CURRENCY);
        }
    }

    /**
     * Units of the limits' currency per unit of {@code currency}, at <em>mid</em>.
     *
     * <p>Mid, not the rate a customer would be paid: a limit measures how much value is leaving, and
     * the bank's own spread is not part of that. The same convention FxPositionService uses to value
     * the book, for the same reason. The directly quoted rate, never an inverted one -- the seeded
     * book disagrees with itself by up to 0.64% between the two.
     *
     * <p>No rate means the debit is refused, because a control that cannot be evaluated must fail
     * closed. In practice V12 makes this unreachable for any account that can actually hold money:
     * a currency with no rate also has no cash account, so nothing can be deposited into it.
     */
    private BigDecimal rateToLimitCurrency(String currency) {
        return currency.equals(LIMIT_CURRENCY)
                ? BigDecimal.ONE
                : fxRateService.require(currency, LIMIT_CURRENCY).getMidRate();
    }

    private static BigDecimal toLimitCurrency(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(Money.SCALE, RoundingMode.HALF_UP);
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
