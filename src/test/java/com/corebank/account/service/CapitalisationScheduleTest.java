package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.Account;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.repository.AccountRepository;
import com.corebank.config.MigrationSql;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * When interest is paid out. It used to be the 1st and only the 1st: a 1st with no tick at all --
 * a deploy down overnight, a crash loop, a database outage -- and every account missed that month's
 * payment, to be paid both months together on the next 1st. Now an account is due from the 1st until
 * it has been paid that month, so a missed 1st is paid on the next tick instead.
 *
 * <p>Every date is passed in rather than read off the clock, so these can be any day.
 */
@SpringBootTest
class CapitalisationScheduleTest {

    @Autowired private InterestService interestService;
    @Autowired private AccountService accountService;
    @Autowired private CustomerService customerService;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    private UUID customerId;

    @BeforeEach
    void setUp() {
        customerId = customerService.create(new CreateCustomerRequest("Tara", "Joshi",
                "tara." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, null, TestDeciders.STAFF);
    }

    /** A savings account holding 10,000, opened on {@code opened}, with a day's interest per day listed. */
    private UUID account(String opened, String... accrualDays) {
        UUID id = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(id, new AmountRequest(new BigDecimal("10000.00"), "INR", "Funding"),
                "cap-" + UUID.randomUUID());
        jdbc.update("update account set opened_at = ? where id = ?", Timestamp.from(Instant.parse(opened)), id);
        for (String day : accrualDays) {
            interestService.accrue(id, LocalDate.parse(day));
        }
        return id;
    }

    private void runOn(String day) {
        new InterestRunner(interestService).capitaliseAll(LocalDate.parse(day));
    }

    private Account reload(UUID id) {
        return accounts.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("a 1st the run never reached is paid on the next tick, not next month")
    void aMissedFirstIsPaidTheNextDay() {
        UUID id = account("2026-09-15T00:00:00Z", "2026-09-28", "2026-09-29", "2026-09-30");

        // No run at all on 1 October; the next one is on the 2nd.
        runOn("2026-10-02");

        // Three days at 0.9589: 2.87 paid, the fraction carried.
        assertThat(reload(id).getBalance()).isEqualByComparingTo("10002.87");
        assertThat(reload(id).getInterestCapitalisedOn()).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    @DisplayName("an account paid this month is not paid again until the next 1st")
    void notPaidTwiceInAMonth() {
        UUID id = account("2026-09-15T00:00:00Z", "2026-09-29", "2026-09-30");
        runOn("2026-10-01");
        assertThat(reload(id).getBalance()).isEqualByComparingTo("10001.91");

        interestService.accrue(id, LocalDate.of(2026, 10, 2));
        interestService.accrue(id, LocalDate.of(2026, 10, 3));
        // Every tick runs the payout now. A whole paisa is accrued again, but October is paid.
        runOn("2026-10-03");
        assertThat(reload(id).getBalance()).isEqualByComparingTo("10001.91");

        runOn("2026-11-01");
        assertThat(reload(id).getBalance()).isGreaterThan(new BigDecimal("10001.91"));
        assertThat(reload(id).getInterestCapitalisedOn()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("an account opened mid-month is first paid on the next 1st, as before")
    void aNewAccountWaitsForTheNextFirst() {
        UUID id = account("2026-10-15T09:00:00Z", "2026-10-15", "2026-10-16", "2026-10-17");

        runOn("2026-10-18");
        assertThat(reload(id).getBalance()).isEqualByComparingTo("10000.00");

        runOn("2026-11-01");
        assertThat(reload(id).getBalance()).isEqualByComparingTo("10002.87");
    }

    @Test
    @DisplayName("a staff pay-out counts as this month's payment")
    void aPayOutCountsAsPaid() {
        UUID id = account("2026-09-15T00:00:00Z", "2026-09-29", "2026-09-30");

        interestService.capitalise(id, LocalDate.of(2026, 10, 1));
        interestService.accrue(id, LocalDate.of(2026, 10, 2));
        interestService.accrue(id, LocalDate.of(2026, 10, 3));
        runOn("2026-10-03");

        assertThat(reload(id).getBalance()).isEqualByComparingTo("10001.91");
    }

    @Test
    @DisplayName("the migration back-fills the last payout from each account's latest interest posting")
    void theMigrationBackFillsTheLastPayout() {
        // Without this every existing account would read as never paid, and every one paid on the 1st
        // would be paid again, early, on the day this deploys.
        UUID paid = account("2026-09-15T00:00:00Z", "2026-09-29", "2026-09-30");
        UUID unpaid = account("2026-09-15T00:00:00Z");
        String reference = interestService.capitalise(paid, LocalDate.of(2026, 10, 1)).orElseThrow();
        jdbc.update("update bank_transaction set posted_at = ? where reference = ?",
                Timestamp.from(Instant.parse("2026-10-01T09:00:00Z")), reference);
        jdbc.update("update account set interest_capitalised_on = null where id in (?, ?)", paid, unpaid);

        // In a UTC session. H2 converts a timestamp to the session's zone whatever AT TIME ZONE says,
        // so in another zone this would be testing H2 rather than the statement. That the date is
        // UTC's whatever the session's zone is, is a PostgreSQL property, and
        // CoreBankTestcontainersIT checks it there with the session set to India.
        MigrationSql.runFor(jdbc, MigrationSql.statement(MIGRATION, "UPDATE account"), "UTC", paid, unpaid);

        assertThat(reload(paid).getInterestCapitalisedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(reload(unpaid).getInterestCapitalisedOn()).isNull();
    }

    private static final String MIGRATION = "V22__interest_capitalised_on.sql";
}
