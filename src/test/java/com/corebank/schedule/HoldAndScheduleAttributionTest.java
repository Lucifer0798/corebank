package com.corebank.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.HoldResponse;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.repository.AccountHoldRepository;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.HoldExpiryRunner;
import com.corebank.account.service.HoldService;
import com.corebank.common.security.Actor;
import com.corebank.common.security.Actors;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.schedule.domain.ScheduleEventType;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.dto.ScheduledTransferEventResponse;
import com.corebank.schedule.dto.ScheduledTransferResponse;
import com.corebank.schedule.repository.ScheduledTransferEventRepository;
import com.corebank.schedule.service.ScheduledTransferService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.ReferenceGenerator;
import com.corebank.transaction.service.TransactionService;
import com.corebank.transaction.service.VelocityLimits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who placed and settled a hold, who set up a standing order, and who changed it since. A hold
 * reserves a customer's money and a standing order moves it every month; neither used to say who.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HoldAndScheduleAttributionTest {

    private static final Actor ANIL = new Actor("teller-anil-subject", "anil");
    private static final Actor PRIYA = new Actor("admin-priya-subject", "priya");

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private HoldService holdService;
    @Autowired private ScheduledTransferService scheduledTransfers;
    @Autowired private AccountHoldRepository holds;
    @Autowired private ReferenceGenerator referenceGenerator;
    @Autowired private VelocityLimits velocityLimits;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID payer;
    private UUID payee;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ZoneOffset.UTC);
        UUID customerId = customerService.create(new CreateCustomerRequest("Rohan", "Mehta",
                "rohan." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        payer = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        payee = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(payer, new AmountRequest(new BigDecimal("1000.00"), "INR", "Fund"), "attr-" + UUID.randomUUID());
    }

    private <T> T as(Actor actor, java.util.function.Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        Actors.runAs(actor, () -> result.set(work.get()));
        return result.get();
    }

    private HoldResponse place() {
        return as(ANIL, () -> holdService.place(payer, new PlaceHoldRequest(new BigDecimal("100.00"), "INR", "Hotel", 24)));
    }

    // --- Holds -----------------------------------------------------------------------------------

    @Test
    @DisplayName("a hold records who placed it, and who released it")
    void releaseIsAttributed() {
        HoldResponse placed = place();
        assertThat(placed.placedBy()).isEqualTo(ANIL);
        assertThat(placed.settledBy()).describedAs("nobody has settled it yet").isNull();

        HoldResponse released = as(PRIYA, () -> holdService.release(placed.reference()));

        assertThat(released.placedBy()).isEqualTo(ANIL);
        assertThat(released.settledBy()).isEqualTo(PRIYA);
    }

    @Test
    @DisplayName("a capture records who captured it")
    void captureIsAttributed() {
        HoldResponse placed = place();

        HoldResponse captured = as(PRIYA, () -> holdService.capture(placed.reference(), new CaptureHoldRequest(null)));

        assertThat(captured.settledBy()).isEqualTo(PRIYA);
    }

    @Test
    @DisplayName("a hold that lapses is recorded as the expiry sweep's, not left blank")
    void expiryIsTheSweeps() {
        String reference = place().reference();
        // A service that believes it is two days later, so the 24-hour hold has lapsed, swept by the
        // real runner. Built by hand, so its locks need a transaction the proxy would otherwise give.
        HoldService later = new HoldService(holds, accountService, transactionService, referenceGenerator,
                velocityLimits, new SimpleMeterRegistry(),
                Clock.fixed(Instant.now().plus(Duration.ofDays(2)), ZoneOffset.UTC));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> new HoldExpiryRunner(later).sweep());

        assertThat(holdService.get(reference).settledBy()).isEqualTo(Actor.system("hold-expiry"));
    }

    // --- Standing orders -------------------------------------------------------------------------

    private ScheduledTransferResponse instruction(String amount) {
        return as(ANIL, () -> scheduledTransfers.create(new CreateScheduledTransferRequest(payer, payee,
                new BigDecimal(amount), "INR", "Rent", ScheduleFrequency.DAILY, today, null)));
    }

    private ScheduledTransferRunner runnerOn(LocalDate date) {
        return new ScheduledTransferRunner(scheduledTransfers, new SimpleMeterRegistry(),
                Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    private List<ScheduledTransferEventResponse> events(UUID schedule) {
        return scheduledTransfers.events(schedule, PageRequest.of(0, 20)).getContent();
    }

    @Test
    @DisplayName("a standing order records who set it up")
    void creationIsAttributed() {
        assertThat(instruction("100.00").createdBy()).isEqualTo(ANIL);
    }

    @Test
    @DisplayName("suspension is the runner's; resume and cancel are whoever did them, with their reasons")
    void itsLifeIsRecorded() {
        UUID schedule = instruction("5000.00").id();
        runnerOn(today).run();
        runnerOn(today.plusDays(1)).run();
        runnerOn(today.plusDays(2)).run();

        as(PRIYA, () -> scheduledTransfers.resume(schedule, "Customer topped up the account"));
        as(ANIL, () -> scheduledTransfers.cancel(schedule, null));

        List<ScheduledTransferEventResponse> history = events(schedule);
        assertThat(history).extracting(ScheduledTransferEventResponse::event).containsExactly(
                ScheduleEventType.CANCELLED, ScheduleEventType.RESUMED, ScheduleEventType.SUSPENDED);
        assertThat(history.get(0).by()).isEqualTo(ANIL);
        assertThat(history.get(0).reason()).isNull();
        assertThat(history.get(1).by()).isEqualTo(PRIYA);
        assertThat(history.get(1).reason()).isEqualTo("Customer topped up the account");
        assertThat(history.get(2).by()).isEqualTo(Actor.system("scheduled-transfer-runner"));
        assertThat(history.get(2).reason())
                .isEqualTo("Stopped after 3 failed attempt(s) in a row: there was not enough money in the account");
    }

    @Test
    @DisplayName("a cancellation over HTTP is attributed to the caller's token, with the reason given")
    void cancelOverHttpTakesTheToken() throws Exception {
        UUID schedule = instruction("100.00").id();

        mockMvc.perform(post("/api/v1/scheduled-transfers/{id}/cancel", schedule)
                        .with(jwt().jwt(token -> token.subject("teller-sunita-subject").claim("preferred_username", "sunita"))
                                .authorities(new SimpleGrantedAuthority("ROLE_TELLER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer moved\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/scheduled-transfers/{id}/events", schedule)
                        .with(jwt().jwt(token -> token.subject("admin")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(jsonPath("$.content[0].event").value("CANCELLED"))
                .andExpect(jsonPath("$.content[0].by.subject").value("teller-sunita-subject"))
                .andExpect(jsonPath("$.content[0].by.name").value("sunita"))
                .andExpect(jsonPath("$.content[0].reason").value("Customer moved"));
    }

    @Test
    @DisplayName("a customer cannot read a standing order's history, and the history cannot be edited")
    void historyIsStaffOnlyAndAppendOnly() throws Exception {
        UUID schedule = instruction("100.00").id();

        mockMvc.perform(get("/api/v1/scheduled-transfers/{id}/events", schedule)
                        .with(jwt().jwt(token -> token.subject("someone")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());

        assertThat(CrudRepository.class.isAssignableFrom(ScheduledTransferEventRepository.class)).isFalse();
        assertThat(Arrays.stream(ScheduledTransferEventRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("update") || name.startsWith("remove"));
    }
}
