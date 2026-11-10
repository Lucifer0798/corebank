package com.corebank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.CaptureHoldRequest;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.dto.PlaceHoldRequest;
import com.corebank.account.service.AccountService;
import com.corebank.account.service.HoldService;
import com.corebank.account.service.InterestService;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.CustomerStatus;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.fx.service.FxPositionService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.dto.ReversalRequest;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.service.TransactionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Each check is shown to fire by corrupting a row directly, the way a bug or a hand-written UPDATE
 * would, and each corruption is undone afterwards.
 *
 * <p>A database of its own, not the suite's shared one. These checks read whole tables, and other
 * tests corrupt rows on purpose and leave them that way, so "clean" would depend on what ran first.
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:corebank-ledger-check;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LedgerCheckTest {

    @Autowired private LedgerCheckService ledgerCheck;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private HoldService holdService;
    @Autowired private InterestService interestService;
    @Autowired private FxPositionService fxPositionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private MockMvc mockMvc;

    private final List<Runnable> undo = new ArrayList<>();

    private UUID customer;
    private UUID account;
    private String accountNumber;

    @BeforeEach
    void setUp() {
        customer = newCustomer();
        account = openAccount(customer, "INR");
        accountNumber = accountService.get(account).accountNumber();
        transactionService.deposit(account, amount("1000.00", "INR"), key());
    }

    /** A check that leaves anything behind would quietly decide the next one's result. */
    @AfterEach
    void undoAndConfirmTheBooksAreStraightAgain() {
        undoCorruptions();
        assertThat(ledgerCheck.check().clean()).as("every corruption undone").isTrue();
    }

    private void undoCorruptions() {
        undo.reversed().forEach(Runnable::run);
        undo.clear();
    }

    private UUID newCustomer() {
        UUID id = customerService.create(new CreateCustomerRequest("Anaya", "Rao",
                "anaya." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(id, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        return id;
    }

    private UUID openAccount(UUID owner, String currency) {
        return accountService.open(new OpenAccountRequest(owner, AccountType.SAVINGS, currency, BigDecimal.ZERO)).id();
    }

    private static AmountRequest amount(String value, String currency) {
        return new AmountRequest(new BigDecimal(value), currency, "Test");
    }

    private static String key() {
        return "lc-" + UUID.randomUUID();
    }

    /** Writes straight to the table, and registers how to put it back. */
    private void corrupt(String sql, String undoSql, Object... args) {
        jdbc.update(sql, args);
        undo.add(() -> jdbc.update(undoSql, args));
    }

    private static LedgerCheckReport.Result result(LedgerCheckReport report, String check) {
        return report.checks().stream().filter(r -> r.check().equals(check)).findFirst().orElseThrow();
    }

    private static List<String> failing(LedgerCheckReport report) {
        return report.checks().stream().filter(r -> !r.discrepancies().isEmpty()).map(LedgerCheckReport.Result::check).toList();
    }

    private double gauge(String check) {
        return meterRegistry.get(LedgerCheckService.METRIC).tag("check", check).gauge().value();
    }

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(token -> token.subject("subject-" + role)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    @DisplayName("real activity of every kind leaves the books clean")
    void everyKindOfPostingKeepsTheBooksStraight() {
        // The check's value rests on this: a false alarm on ordinary banking would make it noise.
        // Every road to the ledger, and every way a running total moves.
        UUID other = openAccount(newCustomer(), "INR");
        UUID dollars = openAccount(newCustomer(), "USD");
        transactionService.deposit(dollars, amount("500.00", "USD"), key());

        transactionService.withdraw(account, amount("100.00", "INR"), key());
        transactionService.transfer(new TransferRequest(account, other, new BigDecimal("50.00"), "INR", "Rent"), key());
        transactionService.transfer(new TransferRequest(dollars, other, new BigDecimal("10.00"), "USD", "FX"), key());
        String mistaken = transactionService.deposit(account, amount("30.00", "INR"), key()).reference();
        transactionService.reverse(mistaken, new ReversalRequest("Keyed twice"), key());

        String captured = holdService.place(account, new PlaceHoldRequest(new BigDecimal("40.00"), "INR", "Hotel", 24)).reference();
        holdService.capture(captured, new CaptureHoldRequest(new BigDecimal("25.00")));
        String released = holdService.place(account, new PlaceHoldRequest(new BigDecimal("20.00"), "INR", "Fuel", 24)).reference();
        holdService.release(released);
        holdService.place(account, new PlaceHoldRequest(new BigDecimal("15.00"), "INR", "Still held", 24));

        interestService.accrue(other, LocalDate.of(2026, 6, 1));
        interestService.accrue(account, LocalDate.of(2026, 6, 1));
        interestService.capitalise(account);
        fxPositionService.revalue();

        // Closed properly: an empty account, then its customer. Neither is a problem.
        UUID leaving = newCustomer();
        UUID emptied = openAccount(leaving, "INR");
        transactionService.deposit(emptied, amount("10.00", "INR"), key());
        transactionService.withdraw(emptied, amount("10.00", "INR"), key());
        accountService.changeStatus(emptied, AccountStatus.CLOSED, "Customer request");
        customerService.changeStatus(leaving, CustomerStatus.CLOSED, "Left the bank");

        LedgerCheckReport report = ledgerCheck.check();

        assertThat(failing(report)).isEmpty();
        assertThat(report.clean()).isTrue();
        assertThat(report.checks()).hasSize(5);
    }

    @Test
    @DisplayName("an entry changed behind the ledger's back unbalances its currency")
    void anUnbalancedLedgerIsFound() {
        UUID entry = jdbc.queryForObject("select id from ledger_entry where account_id = ? fetch first 1 row only", UUID.class, account);
        corrupt("update ledger_entry set amount = amount + 5 where id = ?",
                "update ledger_entry set amount = amount - 5 where id = ?", entry);

        LedgerCheckReport report = ledgerCheck.check();

        assertThat(report.clean()).isFalse();
        assertThat(result(report, "LEDGER_BALANCED").discrepancies()).singleElement().satisfies(found -> {
            assertThat(found.subject()).isEqualTo("INR");
            assertThat(found.detail()).contains("debits").contains("credits");
        });
    }

    @Test
    @DisplayName("a balance that disagrees with its entries is found, and nothing else is blamed")
    void aDriftedBalanceIsFound() {
        corrupt("update account set balance = balance + 5 where id = ?",
                "update account set balance = balance - 5 where id = ?", account);

        LedgerCheckReport report = ledgerCheck.check();

        assertThat(failing(report)).containsExactly("BALANCE_MATCHES_LEDGER");
        assertThat(result(report, "BALANCE_MATCHES_LEDGER").discrepancies()).singleElement().satisfies(found -> {
            assertThat(found.subject()).isEqualTo(accountNumber);
            assertThat(found.detail()).contains("1005").contains("1000");
        });
    }

    @Test
    @DisplayName("a balance on an account with no entries at all is still found")
    void aBalanceWithNoEntriesIsFound() {
        // The left join is the point: an inner one would never see this account.
        UUID empty = openAccount(customer, "INR");
        corrupt("update account set balance = 7 where id = ?", "update account set balance = 0 where id = ?", empty);

        assertThat(result(ledgerCheck.check(), "BALANCE_MATCHES_LEDGER").discrepancies()).hasSize(1);
    }

    @Test
    @DisplayName("a held amount that disagrees with the active holds is found")
    void aDriftedHeldAmountIsFound() {
        holdService.place(account, new PlaceHoldRequest(new BigDecimal("40.00"), "INR", "Hotel", 24));
        corrupt("update account set held_amount = held_amount + 5 where id = ?",
                "update account set held_amount = held_amount - 5 where id = ?", account);

        LedgerCheckReport report = ledgerCheck.check();

        assertThat(failing(report)).containsExactly("HELD_MATCHES_HOLDS");
        assertThat(result(report, "HELD_MATCHES_HOLDS").discrepancies()).singleElement().satisfies(found -> {
            assertThat(found.subject()).isEqualTo(accountNumber);
            assertThat(found.detail()).contains("45").contains("40");
        });
    }

    @Test
    @DisplayName("a held amount with no active hold behind it is found")
    void aHeldAmountWithNoHoldIsFound() {
        corrupt("update account set held_amount = 5 where id = ?", "update account set held_amount = 0 where id = ?", account);

        assertThat(failing(ledgerCheck.check())).containsExactly("HELD_MATCHES_HOLDS");
    }

    @Test
    @DisplayName("a closed account still holding money is found -- what stranded interest used to leave behind")
    void aClosedAccountWithMoneyIsFound() {
        corrupt("update account set status = 'CLOSED' where id = ?", "update account set status = 'ACTIVE' where id = ?", account);

        assertThat(result(ledgerCheck.check(), "CLOSED_ACCOUNTS_EMPTY").discrepancies()).singleElement()
                .satisfies(found -> assertThat(found.subject()).isEqualTo(accountNumber));
    }

    @Test
    @DisplayName("a closed account owed interest is found, but not for a fraction of a paisa")
    void aClosedAccountOwedInterestIsFound() {
        UUID closed = openAccount(customer, "INR");
        corrupt("update account set status = 'CLOSED', accrued_interest = 0.0099 where id = ?",
                "update account set status = 'ACTIVE', accrued_interest = 0 where id = ?", closed);
        // Less than can be paid in real money, which is why closure allows it too.
        assertThat(failing(ledgerCheck.check())).isEmpty();

        corrupt("update account set accrued_interest = 0.01 where id = ?",
                "update account set accrued_interest = 0.0099 where id = ?", closed);
        assertThat(failing(ledgerCheck.check())).containsExactly("CLOSED_ACCOUNTS_EMPTY");
    }

    @Test
    @DisplayName("a closed customer still holding an open account is found")
    void aClosedCustomerWithAnOpenAccountIsFound() {
        corrupt("update customer set status = 'CLOSED' where id = ?", "update customer set status = 'ACTIVE' where id = ?", customer);

        assertThat(result(ledgerCheck.check(), "CLOSED_CUSTOMERS_HAVE_NO_OPEN_ACCOUNTS").discrepancies()).singleElement()
                .satisfies(found -> assertThat(found.detail()).contains(accountNumber).contains("active"));
    }

    @Test
    @DisplayName("past what it lists, a check says there are more")
    void aLongListIsCutShortAndSaysSo() {
        List<UUID> accounts = new ArrayList<>();
        UUID owner = null;
        for (int i = 0; i <= LedgerCheckService.MAX_LISTED; i++) {
            if (i % 5 == 0) {
                owner = newCustomer();
            }
            accounts.add(openAccount(owner, "INR"));
        }
        for (UUID id : accounts) {
            corrupt("update account set balance = 1 where id = ?", "update account set balance = 0 where id = ?", id);
        }

        LedgerCheckReport.Result result = result(ledgerCheck.check(), "BALANCE_MATCHES_LEDGER");

        assertThat(result.discrepancies()).hasSize(LedgerCheckService.MAX_LISTED);
        assertThat(result.more()).isTrue();
    }

    @Test
    @DisplayName("the metric exists at zero from startup, before any check has run")
    void theMetricExistsBeforeTheFirstCheck() {
        // Or an alert on it could never see the first problem: a gauge that appears for the first
        // time already above zero has no earlier value to have risen from.
        SimpleMeterRegistry fresh = new SimpleMeterRegistry();

        new LedgerCheckService(jdbc, Clock.systemUTC(), fresh);

        assertThat(fresh.find(LedgerCheckService.METRIC).gauges()).hasSize(5)
                .allSatisfy(gauge -> assertThat(gauge.value()).isZero());
    }

    @Test
    @DisplayName("the metric rises when a check fails, and falls when it is fixed")
    void theMetricFollowsTheLastCheck() {
        ledgerCheck.check();
        assertThat(gauge("BALANCE_MATCHES_LEDGER")).isZero();

        corrupt("update account set balance = balance + 5 where id = ?",
                "update account set balance = balance - 5 where id = ?", account);
        ledgerCheck.check();
        assertThat(gauge("BALANCE_MATCHES_LEDGER")).isEqualTo(1);
        assertThat(gauge("HELD_MATCHES_HOLDS")).isZero();

        undoCorruptions();
        ledgerCheck.check();
        assertThat(gauge("BALANCE_MATCHES_LEDGER")).isZero();
    }

    @Test
    @DisplayName("the nightly run logs what it finds as an error, naming the account")
    void theNightlyRunLogsWhatItFinds(CapturedOutput output) {
        corrupt("update account set balance = balance + 5 where id = ?",
                "update account set balance = balance - 5 where id = ?", account);

        new LedgerCheckRunner(ledgerCheck, new SimpleMeterRegistry()).run();

        assertThat(output.getOut()).contains("ERROR").contains("BALANCE_MATCHES_LEDGER").contains(accountNumber);
    }

    @Test
    @DisplayName("the nightly run says nothing is wrong when nothing is, and not as an error")
    void theNightlyRunIsQuietWhenClean(CapturedOutput output) {
        new LedgerCheckRunner(ledgerCheck, new SimpleMeterRegistry()).run();

        assertThat(output.getOut()).contains("Ledger check found nothing wrong");
        assertThat(output.getOut().lines())
                .noneMatch(line -> line.contains("LedgerCheckRunner") && line.contains("ERROR"));
    }

    @Test
    @DisplayName("an admin can run it and read what it found")
    void anAdminCanRunIt() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ledger-check").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clean").value(true))
                .andExpect(jsonPath("$.checks.length()").value(5));

        corrupt("update account set balance = balance + 5 where id = ?",
                "update account set balance = balance - 5 where id = ?", account);

        mockMvc.perform(get("/api/v1/admin/ledger-check").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clean").value(false))
                .andExpect(jsonPath("$.checks[1].check").value("BALANCE_MATCHES_LEDGER"))
                .andExpect(jsonPath("$.checks[1].discrepancies[0].subject").value(accountNumber));
    }

    @Test
    @DisplayName("nobody else can: it lists every account that is wrong")
    void onlyAnAdminCanRunIt() throws Exception {
        mockMvc.perform(get("/api/v1/admin/ledger-check").with(as("TELLER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/ledger-check").with(as("CUSTOMER"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("running it changes nothing")
    void itIsReadOnly() {
        corrupt("update account set balance = balance + 5 where id = ?",
                "update account set balance = balance - 5 where id = ?", account);
        BigDecimal before = jdbc.queryForObject("select balance from account where id = ?", BigDecimal.class, account);

        ledgerCheck.check();

        assertThat(jdbc.queryForObject("select balance from account where id = ?", BigDecimal.class, account))
                .isEqualByComparingTo(before);
    }
}
