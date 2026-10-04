package com.corebank.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.EntryDirection;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.domain.TransactionType;
import java.math.BigDecimal;
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
