package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.HoldStatus;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.HoldResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.HoldService;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.ReversalRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.messaging.TransactionPostedEvent;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * When a posting is reversed, everything derived from it has to say so.
 *
 * <p>A reversal has always posted correctly -- the balances were never wrong. What was wrong was
 * every record that pointed at the reversed posting and was never told: a hold still claiming its
 * capture stood, and a search index still showing the transaction as live. Each test here is one of
 * those records.
 */
@SpringBootTest
@RecordApplicationEvents
class ReversalPropagationTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private HoldService holdService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ApplicationEvents events;

    private UUID accountId;
    private int n;

    @BeforeEach
    void setUp() {
        n = UNIQUE.incrementAndGet();
        UUID customerId = customerService.create(new CreateCustomerRequest(
                "Tariq", "Hussain", "tariq.hussain." + n + "@example.com", null,
                LocalDate.of(1984, 8, 15))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED);
        accountId = accountService.open(new OpenAccountRequest(
                customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("10000.00"), "INR", "Funding"), "rp-fund-" + n);
    }

    @Test
    @DisplayName("reversing a hold's capture tells the hold")
    void reversingACaptureMarksTheHold() {
        // Before this PR: the hold stayed CAPTURED, naming a posting that had been undone, so it went
        // on claiming the merchant had been paid. The money was right; the record was false.
        HoldResponse hold = holdService.place(accountId,
                new PlaceHoldRequest(new BigDecimal("400.00"), "INR", "Hotel", null));
        HoldResponse captured = holdService.capture(hold.reference(), new CaptureHoldRequest(null));

        TransactionResponse reversal = transactionService.reverse(captured.capturedTransactionReference(),
                new ReversalRequest("Charged in error"), "rp-cap-rev-" + n);

        HoldResponse after = holdService.get(hold.reference());
        assertThat(after.status()).isEqualTo(HoldStatus.CAPTURE_REVERSED);
        assertThat(after.captureReversalReference()).isEqualTo(reversal.reference());
        assertThat(after.capturedTransactionReference())
                .describedAs("the capture really happened, so the record of it is kept")
                .isEqualTo(captured.capturedTransactionReference());
    }

    @Test
    @DisplayName("reversing an ordinary withdrawal touches no hold")
    void reversingAnUnrelatedWithdrawalLeavesHoldsAlone() {
        // The guard against over-reach: propagation must find the hold by the posting it captured
        // into, not by account. A plain withdrawal on an account that also has a captured hold is
        // not that hold's capture.
        HoldResponse hold = holdService.place(accountId,
                new PlaceHoldRequest(new BigDecimal("400.00"), "INR", "Hotel", null));
        holdService.capture(hold.reference(), new CaptureHoldRequest(null));
        TransactionResponse atm = transactionService.withdraw(accountId,
                new AmountRequest(new BigDecimal("100.00"), "INR", "ATM"), "rp-atm-" + n);

        transactionService.reverse(atm.reference(), new ReversalRequest("Mistake"), "rp-atm-rev-" + n);

        assertThat(holdService.get(hold.reference()).status()).isEqualTo(HoldStatus.CAPTURED);
    }

    @Test
    @DisplayName("reversing a posting re-publishes it as REVERSED, so search can update it")
    void reversingRepublishesTheOriginal() {
        // Before this PR the event carried no status at all, and only the correcting transaction was
        // ever published. The search index kept the original as it was first written: indistinguishable
        // from a live transaction. Re-publishing it -- keyed by the same reference, which the indexer
        // upserts on -- overwrites that document with the truth.
        TransactionResponse deposit = transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("250.00"), "INR", "Mistake"), "rp-dep-" + n);
        events.clear();

        transactionService.reverse(deposit.reference(), new ReversalRequest("Keyed twice"), "rp-dep-rev-" + n);

        assertThat(events.stream(TransactionPostedEvent.class)
                .filter(event -> event.reference().equals(deposit.reference())))
                .describedAs("the original must go out again, carrying its new status")
                .singleElement()
                .extracting(TransactionPostedEvent::status)
                .isEqualTo(TransactionStatus.REVERSED);
    }

    @Test
    @DisplayName("the correcting transaction itself is published as an ordinary posting")
    void theReversalIsPublishedAsPosted() {
        TransactionResponse deposit = transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("250.00"), "INR", "Mistake"), "rp-dep2-" + n);
        events.clear();

        TransactionResponse reversal = transactionService.reverse(deposit.reference(),
                new ReversalRequest("Keyed twice"), "rp-dep2-rev-" + n);

        assertThat(events.stream(TransactionPostedEvent.class)
                .filter(event -> event.reference().equals(reversal.reference())))
                .singleElement()
                .extracting(TransactionPostedEvent::status)
                .isEqualTo(TransactionStatus.POSTED);
    }
}
