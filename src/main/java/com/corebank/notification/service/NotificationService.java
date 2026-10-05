package com.corebank.notification.service;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.EntryDirection;
import com.corebank.account.repository.AccountRepository;
import com.corebank.common.Money;
import com.corebank.common.exception.FailureReason;
import com.corebank.notification.domain.Notification;
import com.corebank.notification.domain.NotificationKind;
import com.corebank.notification.dto.NotificationResponse;
import com.corebank.notification.repository.NotificationRepository;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a posting into what each affected customer is told.
 *
 * <p>Four decisions live here, each of which the obvious implementation gets wrong:
 *
 * <ul>
 *   <li><strong>Once per message, however often it arrives.</strong> Kafka delivers at least once and
 *       the outbox relay retries, so the same message can be handed over more than once. Each
 *       notification is keyed by (reference, account, status) and checked before it is written,
 *       with a unique constraint behind the check. The check is race-free for the case that matters
 *       -- a redelivery -- because messages are keyed by reference, so every copy of one lands on
 *       the same partition and is consumed by the same thread, in order.
 *   <li><strong>One notification per reversal, not two.</strong> A reversal produces two events: the
 *       correcting REVERSAL posting, and the original re-published as REVERSED. Announcing both would
 *       tell the customer about one reversal twice, once as "500 debited" and once as "your credit
 *       was reversed". The re-published original carries the meaning, so the REVERSAL posting is
 *       not announced at all.
 *   <li><strong>Per account, in that account's currency.</strong> A transfer touches two customer
 *       accounts and tells each its own side; general-ledger legs belong to the bank and tell nobody.
 *       Amounts are given in the account's currency, which on the receiving side of an FX transfer
 *       is not the transaction's.
 *   <li><strong>Only what is happening now.</strong> The admin replay, and search rebuilding a lost
 *       index, re-publish history through the same topic -- from the beginning of time, in the
 *       rebuild's case. Those events are marked replayed and announce nothing, or a wiped OpenSearch
 *       volume would alert every customer about every posting they ever had.
 * </ul>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final AccountRepository accounts;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, AccountRepository accounts,
                               MeterRegistry meterRegistry, Clock clock) {
        this.notifications = notifications;
        this.accounts = accounts;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** Writes whatever this event should tell customers, and returns how many notifications that was. */
    @Transactional
    public int handle(TransactionPostedEvent event) {
        if (event.wasReplayed()) {
            // Re-derived from the ledger by a replay or a search-index rebuild, which can cover a
            // bank's entire history. The posting may never have been announced -- interest, before
            // it published at all -- but telling someone today about money that moved last month,
            // as though it just had, is worse than never telling them.
            return 0;
        }
        if (event.type() == TransactionType.REVERSAL) {
            // Announced instead by the original's REVERSED re-publish -- see the class javadoc.
            return 0;
        }

        TransactionStatus status = event.statusOrPosted();
        int written = 0;
        for (TransactionPostedEvent.Leg leg : event.legs()) {
            Optional<Account> found = accounts.findByAccountNumber(leg.accountNumber());
            if (found.isEmpty() || !found.get().isCustomerAccount()) {
                // A general-ledger leg -- cash, an FX position, interest expense. The bank's own books,
                // not anybody's account, so there is no one to tell.
                continue;
            }
            Account account = found.get();
            if (notifications.existsByTransactionReferenceAndAccountIdAndTransactionStatus(
                    event.reference(), account.getId(), status)) {
                // A redelivery. Already told; telling again would be telling twice.
                meterRegistry.counter("corebank.notifications", "outcome", "duplicate").increment();
                continue;
            }

            Notification notification = new Notification();
            notification.setKind(NotificationKind.TRANSACTION);
            notification.setCustomerId(account.getCustomer().getId());
            notification.setAccountId(account.getId());
            notification.setTransactionReference(event.reference());
            notification.setTransactionStatus(status);
            notification.setDirection(leg.direction());
            notification.setAmount(Money.normalize(leg.amount()));
            notification.setCurrency(account.getCurrency());
            notification.setMessage(render(event.type(), status, leg.direction(), leg.amount(),
                    account.getCurrency(), account.getAccountNumber()));
            notification.setCreatedAt(Instant.now(clock));
            notifications.save(notification);

            meterRegistry.counter("corebank.notifications", "outcome", "written").increment();
            written++;
        }
        if (written > 0) {
            log.debug("Wrote {} notification(s) for {} ({})", written, event.reference(), status);
        }
        return written;
    }

    /**
     * Tells the payer a standing instruction did not pay.
     *
     * <p>Called in the same transaction that records the failure, not through Kafka: a refusal posts
     * nothing, so there is no event to consume, and writing both together means the notification
     * exists exactly when the recorded failure does. The caller only gets here once per occurrence
     * -- its guard refuses to record the same occurrence twice -- and uk_notification_schedule_once
     * stands behind that.
     *
     * <p>Only the payer is told. The payee's money did not arrive either, but why is the payer's
     * business, and "there was not enough money" about someone else's account is not the payee's to
     * read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduledTransferMissed(MissedScheduledTransfer missed) {
        Account source = accounts.findById(missed.sourceAccountId()).orElseThrow();

        Notification notification = new Notification();
        notification.setKind(missed.outcome() == MissedScheduledTransfer.Outcome.STOPPED
                ? NotificationKind.SCHEDULED_TRANSFER_SUSPENDED
                : NotificationKind.SCHEDULED_TRANSFER_FAILED);
        notification.setCustomerId(source.getCustomer().getId());
        notification.setAccountId(source.getId());
        notification.setScheduledTransferId(missed.scheduledTransferId());
        notification.setDueOn(missed.dueOn());
        notification.setAmount(Money.normalize(missed.amount()));
        notification.setCurrency(missed.currency());
        notification.setMessage(renderMissed(missed, source.getAccountNumber()));
        notification.setCreatedAt(Instant.now(clock));
        notifications.save(notification);

        meterRegistry.counter("corebank.notifications", "outcome", "schedule-missed").increment();
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> forCustomer(UUID customerId, Pageable pageable) {
        return notifications.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                .map(NotificationResponse::from);
    }

    /**
     * The sentence a customer reads. A customer account's normal balance is CREDIT, so a credit leg
     * is money arriving and a debit leg is money leaving.
     *
     * <p>Amounts are written with the currency code rather than a symbol: unambiguous across the
     * four currencies the bank deals in, and nothing here has to know how each one is written.
     * Account numbers are masked to their last four digits, because a notification is shown on
     * screens and may one day be sent somewhere less private than this database.
     *
     * <p>Package-private and static so every wording can be tested without a database.
     */
    static String render(TransactionType type, TransactionStatus status, EntryDirection direction,
                         BigDecimal amount, String currency, String accountNumber) {
        String money = Money.normalize(amount).toPlainString() + " " + currency;
        String account = masked(accountNumber);

        if (status == TransactionStatus.REVERSED) {
            return direction == EntryDirection.CREDIT
                    ? "A credit of " + money + " to account " + account + " was reversed"
                    : "A debit of " + money + " from account " + account
                            + " was reversed; the money is back in the account";
        }
        if (type == TransactionType.INTEREST) {
            return money + " interest paid into account " + account;
        }
        return direction == EntryDirection.CREDIT
                ? money + " credited to account " + account
                : money + " debited from account " + account;
    }

    /**
     * "Your scheduled transfer of 750.00 INR from account XXXX0001, due 1 Nov 2026, was not made:
     * there was not enough money in the account. The next one is due 1 Dec 2026."
     *
     * <p>The reason comes from the failure's code, never its message -- see {@link FailureReason}.
     * The instruction's own description is left out: it is free text of any length, and the
     * amount, account and date already say which payment this was.
     */
    static String renderMissed(MissedScheduledTransfer missed, String accountNumber) {
        String money = Money.normalize(missed.amount()).toPlainString() + " " + missed.currency();
        String sentence = "Your scheduled transfer of " + money + " from account " + masked(accountNumber)
                + ", due " + DAY.format(missed.dueOn()) + ", was not made: "
                + FailureReason.describe(missed.failureCode()) + ".";
        return switch (missed.outcome()) {
            case RETRYING -> sentence + " The next one is due " + DAY.format(missed.nextRunOn()) + ".";
            case STOPPED -> sentence + " After " + missed.consecutiveFailures()
                    + " failed attempts in a row it has been stopped; contact your branch to restart it.";
            case ENDED -> sentence + " It was the last payment on this instruction.";
        };
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    static String masked(String accountNumber) {
        return accountNumber.length() <= 4
                ? accountNumber
                : "XXXX" + accountNumber.substring(accountNumber.length() - 4);
    }
}
