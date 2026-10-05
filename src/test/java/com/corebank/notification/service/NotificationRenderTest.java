package com.corebank.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.EntryDirection;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a customer actually reads. Each wording is a promise about which way the money went, and a
 * notification that says "credited" for money that left is worse than none at all.
 */
class NotificationRenderTest {

    private static String render(TransactionType type, TransactionStatus status, EntryDirection direction) {
        return NotificationService.render(type, status, direction, new BigDecimal("500.00"), "INR", "100100000001");
    }

    @Test
    @DisplayName("money arriving reads as credited, money leaving as debited")
    void directionDecidesTheWording() {
        // A customer account's normal balance is CREDIT, so a credit leg is money arriving. Get this
        // backwards and every alert tells the customer the opposite of what happened.
        assertThat(render(TransactionType.DEPOSIT, TransactionStatus.POSTED, EntryDirection.CREDIT))
                .isEqualTo("500.00 INR credited to account XXXX0001");
        assertThat(render(TransactionType.WITHDRAWAL, TransactionStatus.POSTED, EntryDirection.DEBIT))
                .isEqualTo("500.00 INR debited from account XXXX0001");
    }

    @Test
    @DisplayName("interest says it is interest")
    void interestIsNamed() {
        assertThat(render(TransactionType.INTEREST, TransactionStatus.POSTED, EntryDirection.CREDIT))
                .isEqualTo("500.00 INR interest paid into account XXXX0001");
    }

    @Test
    @DisplayName("a reversed debit says the money is back")
    void aReversedDebitReassures() {
        // The leg here is the original's, so a reversed withdrawal still carries DEBIT -- the
        // notification has to read it as "that debit was undone", not as a second debit.
        assertThat(render(TransactionType.WITHDRAWAL, TransactionStatus.REVERSED, EntryDirection.DEBIT))
                .isEqualTo("A debit of 500.00 INR from account XXXX0001 was reversed; the money is back in the account");
    }

    @Test
    @DisplayName("a reversed credit says the credit was taken back")
    void aReversedCreditIsStated() {
        assertThat(render(TransactionType.DEPOSIT, TransactionStatus.REVERSED, EntryDirection.CREDIT))
                .isEqualTo("A credit of 500.00 INR to account XXXX0001 was reversed");
    }

    private static String missed(String code, MissedScheduledTransfer.Outcome outcome, LocalDate next, int failures) {
        return NotificationService.renderMissed(new MissedScheduledTransfer(UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 11, 1), new BigDecimal("750.00"), "INR", code, outcome, next, failures),
                "100100000001");
    }

    @Test
    @DisplayName("a missed standing order says what, when, why, and what happens next")
    void aMissedPaymentIsExplained() {
        assertThat(missed("INSUFFICIENT_FUNDS", MissedScheduledTransfer.Outcome.RETRYING, LocalDate.of(2026, 12, 1), 1))
                .isEqualTo("Your scheduled transfer of 750.00 INR from account XXXX0001, due 1 Nov 2026, was not "
                        + "made: there was not enough money in the account. The next one is due 1 Dec 2026.");
    }

    @Test
    @DisplayName("a stopped standing order says it has stopped, and how to restart it")
    void aStoppedInstructionSaysSo() {
        assertThat(missed("DAILY_LIMIT_EXCEEDED", MissedScheduledTransfer.Outcome.STOPPED, null, 3))
                .endsWith("was not made: it would have gone over the account's daily limit. After 3 failed "
                        + "attempts in a row it has been stopped; contact your branch to restart it.");
    }

    @Test
    @DisplayName("a standing order whose last payment failed does not promise another")
    void anEndedInstructionPromisesNothing() {
        assertThat(missed("INSUFFICIENT_FUNDS", MissedScheduledTransfer.Outcome.ENDED, null, 1))
                .endsWith("It was the last payment on this instruction.")
                .doesNotContain("next one");
    }

    @Test
    @DisplayName("an unrecognised failure gets a reason that gives nothing away")
    void anUnknownFailureIsGeneric() {
        // An account being frozen or closed could be the payee's, and an infrastructure error is
        // nobody's business but ours. Neither gets explained to a customer.
        assertThat(missed("ACCOUNT_FROZEN", MissedScheduledTransfer.Outcome.RETRYING, LocalDate.of(2026, 12, 1), 1))
                .contains("was not made: it could not be processed.");
        assertThat(missed(null, MissedScheduledTransfer.Outcome.RETRYING, LocalDate.of(2026, 12, 1), 1))
                .contains("was not made: it could not be processed.");
    }

    @Test
    @DisplayName("the longest missed-payment message still fits the column")
    void theLongestMessageFits() {
        // message is VARCHAR(255). The STOPPED wording with the largest amount the ledger holds and a
        // two-digit failure count is the worst case; an insert that overflowed would roll back the
        // failure being recorded along with it.
        String longest = NotificationService.renderMissed(new MissedScheduledTransfer(UUID.randomUUID(),
                UUID.randomUUID(), LocalDate.of(2026, 12, 31), new BigDecimal("999999999999999.99"), "INR",
                "DAILY_LIMIT_EXCEEDED", MissedScheduledTransfer.Outcome.STOPPED, null, 99), "100100000001");
        assertThat(longest.length()).isLessThanOrEqualTo(255);
    }

    @Test
    @DisplayName("account numbers are masked to their last four digits")
    void accountNumbersAreMasked() {
        // A notification is shown on screens and may one day be sent somewhere less private than this
        // database. The last four are enough for a customer to know which account.
        assertThat(NotificationService.masked("100100000001")).isEqualTo("XXXX0001");
        assertThat(render(TransactionType.DEPOSIT, TransactionStatus.POSTED, EntryDirection.CREDIT))
                .doesNotContain("100100000001");
    }
}
