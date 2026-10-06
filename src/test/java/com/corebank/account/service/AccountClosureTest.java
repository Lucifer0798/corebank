package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.InsufficientFundsException;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.service.ScheduledTransferService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closing an account over something still attached to it. A zero balance used to be the only
 * check, which let an account close under an authorisation a merchant was relying on, or under a
 * standing order that would then fail -- and notify its payer -- on every occurrence.
 */
@SpringBootTest
class AccountClosureTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private AccountService accountService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private HoldService holdService;

    @Autowired
    private ScheduledTransferService scheduledTransfers;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID customerId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        int n = UNIQUE.incrementAndGet();
        today = LocalDate.now(ZoneOffset.UTC);
        customerId = customerService.create(new CreateCustomerRequest("Meera", "Pillai",
                "meera.pillai." + n + "." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED);
    }

    private UUID savings() {
        return accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
    }

    private UUID instruction(UUID from, UUID to, ScheduleFrequency frequency) {
        return scheduledTransfers.create(new CreateScheduledTransferRequest(
                from, to, new BigDecimal("750.00"), "INR", "Rent", frequency, today, null)).id();
    }

    private void close(UUID accountId) {
        accountService.changeStatus(accountId, AccountStatus.CLOSED);
    }

    private static String codeOf(Throwable ex) {
        return ((BusinessRuleException) ex).getCode();
    }

    @Test
    @DisplayName("an account at zero cannot close under a merchant's outstanding hold")
    void anOutstandingHoldBlocksClosure() {
        // Zero balance, but inside a 500 overdraft -- so the hold could be placed, and the balance
        // check alone would have let this close.
        UUID current = accountService.open(new OpenAccountRequest(
                customerId, AccountType.CURRENT, "INR", new BigDecimal("500.00"))).id();
        String hold = holdService.place(current,
                new PlaceHoldRequest(new BigDecimal("100.00"), "INR", "Hotel", 24)).reference();

        assertThatThrownBy(() -> close(current))
                .extracting(AccountClosureTest::codeOf).isEqualTo("CLOSURE_BLOCKED");
        assertThatThrownBy(() -> close(current)).hasMessageContaining("1 outstanding hold");
        assertThat(accountService.get(current).status()).isEqualTo(AccountStatus.ACTIVE);

        holdService.release(hold);
        close(current);
        assertThat(accountService.get(current).status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("a live standing instruction blocks closure on either side of it")
    void aStandingInstructionBlocksBothAccounts() {
        // The payee side matters as much: the instruction may be another customer's, and closing
        // under it would leave them failing -- and being told so -- on every occurrence.
        UUID payer = savings();
        UUID payee = savings();
        instruction(payer, payee, ScheduleFrequency.MONTHLY);

        assertThatThrownBy(() -> close(payer)).hasMessageContaining("1 standing instruction");
        assertThatThrownBy(() -> close(payee)).hasMessageContaining("1 standing instruction");
    }

    @Test
    @DisplayName("a suspended instruction blocks closure too, and cancelling it clears the way")
    void aSuspendedInstructionMustBeCancelled() {
        // Suspended is not finished: staff can resume it. Cancelling is what retires it, which is
        // why cancelling a suspended instruction is now allowed.
        UUID payer = savings();
        UUID payee = savings();
        UUID schedule = instruction(payer, payee, ScheduleFrequency.DAILY);
        for (int day = 0; day < 3; day++) {
            scheduledTransfers.markFailure(schedule, today.plusDays(day),
                    new InsufficientFundsException("100100000001", BigDecimal.ZERO, new BigDecimal("750.00")));
        }

        assertThatThrownBy(() -> close(payer)).hasMessageContaining("1 standing instruction");

        scheduledTransfers.cancel(schedule);
        close(payer);
        assertThat(accountService.get(payer).status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("everything outstanding is listed in one refusal")
    void everythingIsListedAtOnce() {
        UUID current = accountService.open(new OpenAccountRequest(
                customerId, AccountType.CURRENT, "INR", new BigDecimal("500.00"))).id();
        UUID other = savings();
        holdService.place(current, new PlaceHoldRequest(new BigDecimal("100.00"), "INR", "Hotel", 24));
        instruction(current, other, ScheduleFrequency.MONTHLY);
        instruction(other, current, ScheduleFrequency.WEEKLY);

        assertThatThrownBy(() -> close(current))
                .hasMessageContaining("1 outstanding hold")
                .hasMessageContaining("2 standing instructions");
    }

    @Test
    @DisplayName("finished and cancelled instructions do not block closure")
    void finishedInstructionsDoNotBlock() {
        UUID payer = savings();
        UUID payee = savings();
        UUID oneOff = instruction(payer, payee, ScheduleFrequency.ONCE);
        scheduledTransfers.markSuccess(oneOff, today);
        UUID cancelled = instruction(payer, payee, ScheduleFrequency.MONTHLY);
        scheduledTransfers.cancel(cancelled);

        close(payer);
        assertThat(accountService.get(payer).status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("an instruction set up while the account is closing waits, then is refused")
    void creatingAnInstructionCannotRaceAClosure() throws Exception {
        // Closure locks the row and then checks nothing names the account. If setting up an
        // instruction read the account without that lock, it would see it still open, slip in
        // after the check, and leave a live instruction on a closed account.
        UUID payer = savings();
        UUID payee = savings();
        TransactionTemplate inTransaction = new TransactionTemplate(transactionManager);
        CountDownLatch closerHoldsTheLock = new CountDownLatch(1);

        CompletableFuture<Void> closer = CompletableFuture.runAsync(() -> inTransaction.executeWithoutResult(status -> {
            accountService.requireForUpdate(payee);
            closerHoldsTheLock.countDown();
            pause(300);
            close(payee);
        }));
        assertThat(closerHoldsTheLock.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> instruction(payer, payee, ScheduleFrequency.MONTHLY))
                .describedAs("it must wait for the closure to commit, then see the account closed")
                .extracting(AccountClosureTest::codeOf).isEqualTo("ACCOUNT_CLOSED");
        closer.get(5, TimeUnit.SECONDS);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
