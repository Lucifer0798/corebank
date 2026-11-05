package com.corebank.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.domain.HoldStatus;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.HoldService;
import com.corebank.account.service.InterestService;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.security.Actor;
import com.corebank.common.security.Actors;
import com.corebank.config.TestActorListener;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.CustomerStatusChange;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.messaging.CustomerChangedEvent;
import com.corebank.customer.repository.CustomerStatusChangeRepository;
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
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suspending and closing a customer. Both statuses have been in the schema since V1, and the
 * application has acted on them -- no new accounts, no money leaving, no KYC on a closed customer --
 * but nothing could ever set them, so none of those guards had once fired on a real customer. A
 * customer who left stayed ACTIVE for good, and the only way to stop one was account by account.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class CustomerLifecycleTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private HoldService holdService;
    @Autowired private InterestService interestService;
    @Autowired private ScheduledTransferService scheduledTransfers;
    @Autowired private CustomerStatusChangeRepository changes;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEvents events;

    private UUID customer;
    private UUID account;
    private UUID otherAccount;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ZoneOffset.UTC);
        customer = newCustomer("Rohan");
        account = fundedAccount(customer);
        otherAccount = fundedAccount(newCustomer("Sara"));
    }

    private UUID newCustomer(String name) {
        UUID id = customerService.create(new CreateCustomerRequest(name, "Bose",
                name.toLowerCase() + "." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(id, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        return id;
    }

    private UUID openAccount(UUID owner) {
        return accountService.open(new OpenAccountRequest(owner, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
    }

    private UUID fundedAccount(UUID owner) {
        UUID id = openAccount(owner);
        transactionService.deposit(id, amount("1000.00"), key());
        return id;
    }

    private static AmountRequest amount(String value) {
        return new AmountRequest(new BigDecimal(value), "INR", "Test");
    }

    private static String key() {
        return "life-" + UUID.randomUUID();
    }

    private void suspend() {
        customerService.changeStatus(customer, CustomerStatus.SUSPENDED, "Fraud report");
    }

    private BigDecimal balanceOf(UUID id) {
        return accountService.get(id).balance();
    }

    private static void assertRefused(String code, ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo(code);
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-priya-subject").claim("preferred_username", "priya"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor teller() {
        return jwt().jwt(token -> token.subject("teller-anil-subject").claim("preferred_username", "anil"))
                .authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    private static RequestPostProcessor aCustomer() {
        return jwt().jwt(token -> token.subject("some-customer")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    // --- Suspending ---------------------------------------------------------------------------

    @Test
    @DisplayName("a suspended customer cannot send money or open an account, and is told so by name")
    void suspensionStopsMoneyLeaving() {
        suspend();

        assertRefused("CUSTOMER_NOT_ACTIVE", () -> transactionService.withdraw(account, amount("100.00"), key()));
        assertRefused("CUSTOMER_NOT_ACTIVE", () -> transactionService.transfer(
                new TransferRequest(account, otherAccount, new BigDecimal("100.00"), "INR", "Out"), key()));
        assertRefused("CUSTOMER_NOT_ACTIVE", () -> holdService.place(account,
                new PlaceHoldRequest(new BigDecimal("100.00"), "INR", "Hotel", 24)));
        assertRefused("CUSTOMER_NOT_ACTIVE", () -> scheduledTransfers.create(new CreateScheduledTransferRequest(
                account, otherAccount, new BigDecimal("100.00"), "INR", "Rent", ScheduleFrequency.MONTHLY, today, null)));
        assertRefused("CUSTOMER_NOT_ELIGIBLE", () -> openAccount(customer));

        assertThatThrownBy(() -> transactionService.withdraw(account, amount("100.00"), key()))
                .hasMessageContaining("is suspended");
        assertThat(balanceOf(account)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("payments in still arrive: a deposit, an incoming transfer, interest")
    void paymentsInStillArrive() {
        interestService.accrue(account, LocalDate.of(2026, 6, 1));
        suspend();

        transactionService.deposit(account, amount("100.00"), key());
        transactionService.transfer(new TransferRequest(otherAccount, account, new BigDecimal("100.00"), "INR", "In"), key());
        assertThat(interestService.capitalise(account)).isPresent();

        // 0.0959 a day on 1,000: 0.09 paid.
        assertThat(balanceOf(account)).isEqualByComparingTo("1200.09");
    }

    @Test
    @DisplayName("reinstating lifts it at once")
    void reinstatingLiftsIt() {
        suspend();
        customerService.changeStatus(customer, CustomerStatus.ACTIVE, null);

        transactionService.withdraw(account, amount("100.00"), key());
        assertThat(balanceOf(account)).isEqualByComparingTo("900.00");
        assertThat(openAccount(customer)).isNotNull();
    }

    @Test
    @DisplayName("a reversal still goes through, even when it takes money back out")
    void reversalsAreExempt() {
        String deposit = transactionService.deposit(account, amount("300.00"), key()).reference();
        suspend();

        transactionService.reverse(deposit, new ReversalRequest("Keyed against the wrong account"), key());

        assertThat(transactionService.getByReference(deposit).status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(balanceOf(account)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("an earlier hold cannot be captured while suspended, and is still there afterwards")
    void aHoldFollowsTheSameRuleAsAFreezeOrALapsedKyc() {
        // A capture is a withdrawal. The same policy a freeze and a KYC restriction have, so changing
        // it is a decision about all three rather than an accident of this one.
        String hold = holdService.place(account, new PlaceHoldRequest(new BigDecimal("100.00"), "INR", "Hotel", 24))
                .reference();
        suspend();

        assertRefused("CUSTOMER_NOT_ACTIVE", () -> holdService.capture(hold, new CaptureHoldRequest(null)));
        assertThat(holdService.get(hold).status()).isEqualTo(HoldStatus.ACTIVE);

        customerService.changeStatus(customer, CustomerStatus.ACTIVE, null);
        assertThat(holdService.capture(hold, new CaptureHoldRequest(null)).status()).isEqualTo(HoldStatus.CAPTURED);
    }

    @Test
    @DisplayName("an existing standing order fails, without telling the payee why")
    void anExistingStandingOrderFailsQuietly() {
        UUID schedule = scheduledTransfers.create(new CreateScheduledTransferRequest(account, otherAccount,
                new BigDecimal("100.00"), "INR", "Rent", ScheduleFrequency.MONTHLY, today, null)).id();
        suspend();

        new ScheduledTransferRunner(scheduledTransfers, new SimpleMeterRegistry(),
                Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)).run();

        ScheduledTransferResponse after = scheduledTransfers.get(schedule);
        assertThat(after.consecutiveFailures()).isEqualTo(1);
        // The instruction is visible to the payee too, and a suspension is not theirs to learn of.
        assertThat(after.lastError()).isEqualTo("It could not be processed (100.00 INR was due).");
        assertThat(balanceOf(account)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a lapsed KYC still reads as a KYC problem, not a suspension")
    void aLapsedKycKeepsItsOwnRefusal() {
        customerService.updateKyc(customer, KycStatus.REJECTED, "Failed re-check", TestDeciders.STAFF);

        assertRefused("CUSTOMER_NOT_VERIFIED", () -> transactionService.withdraw(account, amount("100.00"), key()));
    }

    // --- Closing ------------------------------------------------------------------------------

    private void emptyAndClose(UUID id) {
        transactionService.withdraw(id, amount(balanceOf(id).toPlainString()), key());
        accountService.changeStatus(id, AccountStatus.CLOSED, "Customer request");
    }

    @Test
    @DisplayName("closing is refused while an account is open, and names every one of them")
    void closingNamesEveryOpenAccount() {
        UUID frozen = openAccount(customer);
        accountService.changeStatus(frozen, AccountStatus.FROZEN, "Legal order");
        String number = accountService.get(account).accountNumber();
        String frozenNumber = accountService.get(frozen).accountNumber();

        assertRefused("CLOSURE_BLOCKED", () -> customerService.changeStatus(customer, CustomerStatus.CLOSED, "Left the bank"));
        // A frozen account is not a closed one.
        assertThatThrownBy(() -> customerService.changeStatus(customer, CustomerStatus.CLOSED, "Left the bank"))
                .hasMessageContaining("2 open accounts")
                .hasMessageContaining(number)
                .hasMessageContaining(frozenNumber);
        assertThat(customerService.get(customer).status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("once every account is closed the customer can close, even from suspended")
    void closingWorksOnceTheAccountsAreClosed() {
        emptyAndClose(account);
        suspend();

        customerService.changeStatus(customer, CustomerStatus.CLOSED, "Left the bank");

        assertThat(customerService.get(customer).status()).isEqualTo(CustomerStatus.CLOSED);
    }

    @Test
    @DisplayName("closing is final: no reinstating, no KYC review, no new accounts")
    void closingIsFinal() {
        emptyAndClose(account);
        customerService.changeStatus(customer, CustomerStatus.CLOSED, "Left the bank");

        assertRefused("CUSTOMER_CLOSED", () -> customerService.changeStatus(customer, CustomerStatus.ACTIVE, "Came back"));
        assertRefused("CUSTOMER_CLOSED", () -> customerService.changeStatus(customer, CustomerStatus.SUSPENDED, "Fraud"));
        assertRefused("CUSTOMER_CLOSED", () -> customerService.updateKyc(customer, KycStatus.VERIFIED, null, TestDeciders.STAFF));
        assertRefused("CUSTOMER_NOT_ELIGIBLE", () -> openAccount(customer));
    }

    @Test
    @DisplayName("an account opened while the customer is closing waits, then is refused")
    void openingAnAccountCannotRaceAClosure() throws Exception {
        // Closure locks the customer and then checks no account is open. If opening read the customer
        // without that lock, it would see them still active, slip in after the check, and leave a
        // closed customer holding an open account.
        emptyAndClose(account);
        TransactionTemplate inTransaction = new TransactionTemplate(transactionManager);
        CountDownLatch closerHoldsTheLock = new CountDownLatch(1);

        // Its own thread, so it says who it is: the test's actor is thread-local and does not follow it.
        CompletableFuture<Void> closer = CompletableFuture.runAsync(() -> Actors.runAs(TestActorListener.STAFF,
                () -> inTransaction.executeWithoutResult(status -> {
                    customerService.requireForUpdate(customer);
                    closerHoldsTheLock.countDown();
                    pause(300);
                    customerService.changeStatus(customer, CustomerStatus.CLOSED, "Left the bank");
                })));
        assertThat(closerHoldsTheLock.await(5, TimeUnit.SECONDS)).isTrue();

        assertRefused("CUSTOMER_NOT_ELIGIBLE", () -> openAccount(customer));
        closer.get(5, TimeUnit.SECONDS);
        assertThat(customerService.get(customer).status()).isEqualTo(CustomerStatus.CLOSED);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // --- The rules on a change ----------------------------------------------------------------

    @Test
    @DisplayName("suspending or closing needs a reason, and whitespace is not one; reinstating does not")
    void aReasonIsRequiredToSuspendOrClose() {
        assertRefused("STATUS_REASON_REQUIRED", () -> customerService.changeStatus(customer, CustomerStatus.SUSPENDED, null));
        assertRefused("STATUS_REASON_REQUIRED", () -> customerService.changeStatus(customer, CustomerStatus.SUSPENDED, "   "));
        assertRefused("STATUS_REASON_REQUIRED", () -> customerService.changeStatus(customer, CustomerStatus.CLOSED, " "));
        assertThat(customerService.get(customer).status()).isEqualTo(CustomerStatus.ACTIVE);

        suspend();
        customerService.changeStatus(customer, CustomerStatus.ACTIVE, null);
        assertThat(customerService.get(customer).status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("a change to the status they already have is refused, and records nothing")
    void aNonChangeIsRefused() throws Exception {
        assertRefused("STATUS_UNCHANGED", () -> customerService.changeStatus(customer, CustomerStatus.ACTIVE, null));

        mockMvc.perform(get("/api/v1/customers/{id}/status-changes", customer).with(teller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("search hears about a change")
    void aChangeIsPublished() {
        suspend();

        assertThat(events.stream(CustomerChangedEvent.class))
                .anyMatch(event -> event.id().equals(customer) && event.status() == CustomerStatus.SUSPENDED);
    }

    // --- Over HTTP ----------------------------------------------------------------------------

    @Test
    @DisplayName("the history says who and why, newest first, taken from the caller's token")
    void theHistoryRecordsWhoAndWhy() throws Exception {
        mockMvc.perform(post("/api/v1/customers/{id}/suspend", customer).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Fraud report\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mockMvc.perform(post("/api/v1/customers/{id}/reinstate", customer).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/customers/{id}/status-changes", customer).with(teller()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].toStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.content[1].fromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.content[1].toStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.content[1].reason").value("Fraud report"))
                .andExpect(jsonPath("$.content[1].changedBySubject").value("admin-priya-subject"))
                .andExpect(jsonPath("$.content[1].changedByName").value("priya"));
    }

    @Test
    @DisplayName("a refused closure comes back as a 422 naming what is in the way")
    void closingOverHttp() throws Exception {
        mockMvc.perform(post("/api/v1/customers/{id}/close", customer).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Left the bank\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOSURE_BLOCKED"));
        mockMvc.perform(post("/api/v1/customers/{id}/suspend", customer).with(admin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("STATUS_REASON_REQUIRED"));
    }

    @Test
    @DisplayName("only an admin can change a status; staff can read the history, a customer cannot")
    void whoCanDoWhat() throws Exception {
        for (String action : new String[] {"suspend", "reinstate", "close"}) {
            mockMvc.perform(post("/api/v1/customers/{id}/" + action, customer).with(teller())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Because\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/customers/{id}/" + action, customer).with(aCustomer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Because\"}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(customerService.get(customer).status()).isEqualTo(CustomerStatus.ACTIVE);

        // The reason can be a fraud report or a legal order -- not the customer's to read.
        mockMvc.perform(get("/api/v1/customers/{id}/status-changes", customer).with(aCustomer()))
                .andExpect(status().isForbidden());
    }

    // --- The history itself -------------------------------------------------------------------

    @Test
    @DisplayName("the history cannot be edited: the repository offers no way to delete or update")
    void theHistoryIsAppendOnly() {
        assertThat(CrudRepository.class.isAssignableFrom(CustomerStatusChangeRepository.class)).isFalse();
        assertThat(Arrays.stream(CustomerStatusChangeRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("update")
                        || name.startsWith("remove"));
    }

    @Test
    @DisplayName("the database refuses a suspension with no reason, whoever writes it")
    void theSchemaBacksUpTheReasonRule() {
        CustomerStatusChange unexplained = new CustomerStatusChange(customer, CustomerStatus.ACTIVE,
                CustomerStatus.SUSPENDED, new Actor("someone", "someone"), null, Instant.now());

        assertThatThrownBy(() -> changes.save(unexplained)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
