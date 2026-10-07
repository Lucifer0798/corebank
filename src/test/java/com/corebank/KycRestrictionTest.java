package com.corebank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.HoldStatus;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.HoldService;
import com.corebank.account.service.InterestService;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.schedule.ScheduledTransferRunner;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.dto.ScheduledTransferResponse;
import com.corebank.schedule.service.ScheduledTransferService;
import com.corebank.transaction.domain.TransactionStatus;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.ReversalRequest;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.service.TransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A customer whose KYC lapses after opening -- rejected on a re-check, or sent back to PENDING -- can
 * no longer send money, and can still receive it.
 *
 * <p>Until this, KYC was read once, when an account was opened, and never again: a rejected
 * customer kept full use of every account they already had. Outgoing only is a policy choice --
 * payments in still land, so nobody paying them has a payment bounce for a reason that is none of
 * their business, and the customer is restricted rather than cut off.
 */
@SpringBootTest
class KycRestrictionTest {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private HoldService holdService;

    @Autowired
    private ScheduledTransferService scheduledTransfers;

    @Autowired
    private InterestService interestService;

    private UUID restricted;
    private UUID restrictedAccount;
    private UUID verifiedAccount;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ZoneOffset.UTC);
        restricted = customer("Kiran");
        restrictedAccount = fundedAccount(restricted);
        verifiedAccount = fundedAccount(customer("Leela"));
    }

    private UUID customer(String name) {
        UUID id = customerService.create(new CreateCustomerRequest(name, "Das",
                name.toLowerCase() + "." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(id, KycStatus.VERIFIED);
        return id;
    }

    private UUID fundedAccount(UUID customerId) {
        UUID account = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(account, amount("1000.00"), "fund-" + account);
        return account;
    }

    private static AmountRequest amount(String value) {
        return new AmountRequest(new BigDecimal(value), "INR", "Test");
    }

    private static String key() {
        return "kyc-" + UUID.randomUUID();
    }

    private static void assertNotVerified(Executable action) {
        assertThatThrownBy(action::execute)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("CUSTOMER_NOT_VERIFIED");
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountService.get(accountId).balance();
    }

    @Test
    @DisplayName("a rejected customer cannot send money, and can still receive it")
    void outgoingIsBlockedAndIncomingIsNot() {
        customerService.updateKyc(restricted, KycStatus.REJECTED);

        assertNotVerified(() -> transactionService.withdraw(restrictedAccount, amount("100.00"), key()));
        assertNotVerified(() -> transactionService.transfer(
                new TransferRequest(restrictedAccount, verifiedAccount, new BigDecimal("100.00"), "INR", "Out"), key()));

        transactionService.deposit(restrictedAccount, amount("100.00"), key());
        transactionService.transfer(
                new TransferRequest(verifiedAccount, restrictedAccount, new BigDecimal("100.00"), "INR", "In"), key());

        assertThat(balanceOf(restrictedAccount))
                .describedAs("1000 to start, both refusals moved nothing, the deposit and the incoming transfer landed")
                .isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("interest is still paid in")
    void interestStillArrives() {
        // A system posting crediting the customer -- the clearest case of money owed to them that a
        // restriction on sending must not touch.
        interestService.accrue(restrictedAccount, LocalDate.of(2026, 6, 1));
        customerService.updateKyc(restricted, KycStatus.REJECTED);

        assertThat(interestService.capitalise(restrictedAccount)).isPresent();
        assertThat(balanceOf(restrictedAccount)).isGreaterThan(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("being sent back to PENDING restricts as well as a rejection does")
    void pendingAlsoBlocks() {
        // A re-review in progress is not a verification. The rule is "verified", not "not rejected".
        customerService.updateKyc(restricted, KycStatus.PENDING);

        assertNotVerified(() -> transactionService.withdraw(restrictedAccount, amount("100.00"), key()));
    }

    @Test
    @DisplayName("verifying the customer again lifts the restriction at once")
    void reVerifyingLiftsIt() {
        // Read live from the customer, not recorded on each account, so there is nothing to undo.
        customerService.updateKyc(restricted, KycStatus.REJECTED);
        assertNotVerified(() -> transactionService.withdraw(restrictedAccount, amount("100.00"), key()));

        customerService.updateKyc(restricted, KycStatus.VERIFIED);
        transactionService.withdraw(restrictedAccount, amount("100.00"), key());

        assertThat(balanceOf(restrictedAccount)).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("a reversal still goes through, even when it takes money back out")
    void reversalsAreExempt() {
        // A correction the bank owes must never be refused. Reversing a deposit debits the account.
        String deposit = transactionService.deposit(restrictedAccount, amount("300.00"), key()).reference();
        customerService.updateKyc(restricted, KycStatus.REJECTED);

        transactionService.reverse(deposit, new ReversalRequest("Keyed against the wrong account"), key());

        assertThat(transactionService.getByReference(deposit).status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(balanceOf(restrictedAccount)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("no new holds, and an earlier hold cannot be captured while restricted")
    void holdsFollowTheSameRule() {
        // A hold reserves money to leave. The capture of one placed before the restriction is a
        // withdrawal and is refused like any other -- the same policy a freeze has, pinned in
        // HoldServiceTest -- and the hold stays outstanding to capture once re-verified, or release.
        String earlier = holdService.place(restrictedAccount,
                new PlaceHoldRequest(new BigDecimal("100.00"), "INR", "Hotel", 24)).reference();
        customerService.updateKyc(restricted, KycStatus.REJECTED);

        assertNotVerified(() -> holdService.place(restrictedAccount,
                new PlaceHoldRequest(new BigDecimal("100.00"), "INR", "Hotel", 24)));
        assertNotVerified(() -> holdService.capture(earlier, new CaptureHoldRequest(null)));
        assertThat(holdService.get(earlier).status()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    @DisplayName("no standing order can be set up to pay out, but one paying in can")
    void standingOrdersFromARestrictedCustomerAreRefused() {
        customerService.updateKyc(restricted, KycStatus.REJECTED);

        assertNotVerified(() -> scheduledTransfers.create(new CreateScheduledTransferRequest(restrictedAccount,
                verifiedAccount, new BigDecimal("100.00"), "INR", "Rent", ScheduleFrequency.MONTHLY, today, null)));

        ScheduledTransferResponse incoming = scheduledTransfers.create(new CreateScheduledTransferRequest(
                verifiedAccount, restrictedAccount, new BigDecimal("100.00"), "INR", "Allowance",
                ScheduleFrequency.MONTHLY, today, null));
        assertThat(incoming.id()).isNotNull();
    }

    @Test
    @DisplayName("an existing standing order from a restricted customer fails, without saying why to anyone")
    void anExistingStandingOrderFailsQuietly() {
        // Set up while verified, then restricted. The occurrence is refused like any other debit and
        // recorded as a failure -- with the generic reason, since "your KYC lapsed" is not something
        // to put on a list the payee can read.
        UUID schedule = scheduledTransfers.create(new CreateScheduledTransferRequest(restrictedAccount,
                verifiedAccount, new BigDecimal("100.00"), "INR", "Rent", ScheduleFrequency.MONTHLY, today, null)).id();
        customerService.updateKyc(restricted, KycStatus.REJECTED);

        new ScheduledTransferRunner(scheduledTransfers, new SimpleMeterRegistry(),
                Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)).run();

        ScheduledTransferResponse after = scheduledTransfers.get(schedule);
        assertThat(after.consecutiveFailures()).isEqualTo(1);
        assertThat(after.lastError()).isEqualTo("It could not be processed (100.00 INR was due).");
        assertThat(balanceOf(restrictedAccount)).isEqualByComparingTo("1000.00");
    }
}
