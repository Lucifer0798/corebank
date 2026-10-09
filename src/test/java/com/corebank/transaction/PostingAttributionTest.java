package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.InterestRunner;
import com.corebank.account.service.InterestService;
import com.corebank.common.security.Actor;
import com.corebank.common.security.Actors;
import com.corebank.config.DevDataSeeder;
import com.corebank.config.TestActorListener;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.repository.CustomerRepository;
import com.corebank.customer.service.CustomerService;
import com.corebank.schedule.ScheduledTransferRunner;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.service.ScheduledTransferService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every posting says who made it: the member of staff whose token made the request, or the job
 * that ran with no request behind it. A posting with neither is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostingAttributionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private ScheduledTransferService scheduledTransfers;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID accountId;
    private UUID otherAccountId;

    @BeforeEach
    void setUp() {
        UUID customerId = customerService.create(new CreateCustomerRequest("Ishaan", "Bose",
                "ishaan." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, null, com.corebank.config.TestDeciders.STAFF);
        accountId = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        otherAccountId = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
    }

    @AfterEach
    void clearAnyToken() {
        SecurityContextHolder.clearContext();
    }

    private static RequestPostProcessor staff(String subject, String username, String role) {
        return jwt().jwt(token -> token.subject(subject).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private String depositAs(RequestPostProcessor who, String amount) throws Exception {
        String body = mockMvc.perform(post("/api/v1/accounts/{id}/deposits", accountId).with(who)
                        .header("Idempotency-Key", "attr-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"description\":\"Cash at the counter\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.reference");
    }

    private String[] initiatorOf(String reference) {
        var transaction = transactionService.getByReference(reference);
        return transaction.initiatedBy() == null
                ? null
                : new String[] {transaction.initiatedBy().subject(), transaction.initiatedBy().name()};
    }

    @Test
    @DisplayName("a teller's cash deposit records which teller took it")
    void aTellerIsRecorded() throws Exception {
        String reference = depositAs(staff("teller-ravi-subject", "ravi", "TELLER"), "500.00");

        assertThat(initiatorOf(reference)).containsExactly("teller-ravi-subject", "ravi");
        mockMvc.perform(get("/api/v1/transactions/{reference}", reference)
                        .with(staff("admin-subject", "priya", "ADMIN")))
                .andExpect(jsonPath("$.initiatedBy.subject").value("teller-ravi-subject"))
                .andExpect(jsonPath("$.initiatedBy.name").value("ravi"));
    }

    @Test
    @DisplayName("a reversal records the admin who made it; the original keeps its teller")
    void aReversalRecordsTheAdmin() throws Exception {
        String original = depositAs(staff("teller-ravi-subject", "ravi", "TELLER"), "300.00");

        String body = mockMvc.perform(post("/api/v1/transactions/{reference}/reversal", original)
                        .with(staff("admin-priya-subject", "priya", "ADMIN"))
                        .header("Idempotency-Key", "attr-rev-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Keyed against the wrong account\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(initiatorOf(JsonPath.read(body, "$.reference"))).containsExactly("admin-priya-subject", "priya");
        assertThat(initiatorOf(original))
                .describedAs("reversing a posting does not rewrite who made it")
                .containsExactly("teller-ravi-subject", "ravi");
    }

    @Test
    @DisplayName("a standing order's payment is the runner's, even with somebody's token on the thread")
    void theRunnerIsRecordedOverAnyToken() {
        transactionService.deposit(accountId, new AmountRequest(new BigDecimal("1000.00"), "INR", "Fund"),
                "attr-fund-" + UUID.randomUUID());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        UUID schedule = scheduledTransfers.create(new CreateScheduledTransferRequest(accountId, otherAccountId,
                new BigDecimal("100.00"), "INR", "Rent", ScheduleFrequency.MONTHLY, today, null)).id();
        // A token on the thread, as a request would leave one. The job's own identity must win:
        // nobody signed in made this payment.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "none").subject("someone-else").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build(), List.of()));

        new ScheduledTransferRunner(scheduledTransfers, new SimpleMeterRegistry(),
                Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)).run();

        String reference = transactionService.statement(otherAccountId, null, null,
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().getFirst().reference();
        assertThat(initiatorOf(reference)).containsExactly("system:scheduled-transfer-runner", "scheduled-transfer-runner");
        assertThat(scheduledTransfers.get(schedule).runsCompleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("interest capitalised by the runner is the runner's")
    void theInterestRunnerDeclaresItself() {
        // The real capitalisation needs the first of the month; what is under test is only that the
        // runner says who it is while capitalising, so the service is a stand-in that records it.
        InterestService interest = mock(InterestService.class);
        UUID account = UUID.randomUUID();
        AtomicReference<Actor> seen = new AtomicReference<>();
        when(interest.today()).thenReturn(LocalDate.of(2026, 11, 1));
        when(interest.findAccountsToAccrue(LocalDate.of(2026, 11, 1))).thenReturn(List.of());
        when(interest.isCapitalisationDay()).thenReturn(true);
        when(interest.findAccountsToCapitalise()).thenReturn(List.of(account));
        when(interest.capitalise(account)).thenAnswer(invocation -> {
            seen.set(Actors.current());
            return java.util.Optional.of("TXN-INTEREST");
        });
        Actors.setTestFallback(null);

        new InterestRunner(interest).run();

        assertThat(seen.get()).isEqualTo(Actor.system("interest-runner"));
    }

    @Test
    @DisplayName("the dev seeder's opening deposit is the seeder's")
    void theSeederDeclaresItself() throws Exception {
        // With no fallback and no token, the seeder's deposit can only succeed if it names itself.
        Actors.setTestFallback(null);
        assertThat(customers.existsByEmailIgnoreCase("asha.menon@example.com"))
                .describedAs("the seeder skips itself if Asha exists, which would make this test vacuous")
                .isFalse();

        new DevDataSeeder().seedDevelopmentData(customers, customerService, accountService, transactionService)
                .run(null);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transaction WHERE initiated_by_subject = 'system:dev-data-seeder'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a posting with nobody to attribute it to is refused, and leaves nothing behind")
    void anUnattributedPostingIsRefused() {
        Actors.setTestFallback(null);

        assertThatThrownBy(() -> transactionService.deposit(accountId,
                new AmountRequest(new BigDecimal("100.00"), "INR", "Who?"), "attr-none-" + UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nothing to attribute this to");

        Actors.setTestFallback(TestActorListener.STAFF);
        assertThat(accountService.get(accountId).balance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("nothing in the application sets the test fallback")
    void productionNeverSetsTheFallback() throws IOException {
        // The fallback exists so tests need no token to set up a deposit. Called from production code
        // it would turn every missing attribution into a silent one, so its only caller outside tests
        // must be its own definition.
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            List<Path> callers = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("setTestFallback(");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
            assertThat(callers).extracting(path -> path.getFileName().toString()).containsExactly("Actors.java");
        }
    }
}
