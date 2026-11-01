package com.corebank.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.AccountStatus;
import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
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
 * Days the accrual run never reached. It only ever accrued "today", and an account refuses a day
 * earlier than the last one it was accrued for -- so a day with no run at all (a deploy down
 * overnight, a crash loop, a database outage) was interest the customer never got, with nothing to
 * show it was missing: the account's accrued-through date looked up to date.
 *
 * <p>The ledger knows what every missed day's balance was, and the status history knows whether the
 * account was frozen on it. Postings and status changes are backdated here because both are stamped
 * with the wall clock, and these days are in the past.
 */
@SpringBootTest
class MissedAccrualTest {

    /** A day's interest at 3.5% on 10,000 and on 20,000. */
    private static final BigDecimal ON_10K = new BigDecimal("0.9589");
    private static final BigDecimal ON_20K = new BigDecimal("1.9178");

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);

    @Autowired private InterestService interestService;
    @Autowired private AccountService accountService;
    @Autowired private CustomerService customerService;
    @Autowired private TransactionService transactionService;
    @Autowired private JdbcTemplate jdbc;

    private UUID customerId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        customerId = customerService.create(new CreateCustomerRequest("Ishaan", "Verma",
                "ishaan." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customerId, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        accountId = funded(at("2025-12-31T12:00:00Z"));
    }

    /** A savings account holding 10,000, deposited at {@code when}. */
    private UUID funded(Instant when) {
        UUID id = accountService.open(new OpenAccountRequest(customerId, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        deposit(id, "10000.00", when);
        return id;
    }

    private void deposit(UUID id, String amount, Instant when) {
        String reference = transactionService.deposit(id,
                new AmountRequest(new BigDecimal(amount), "INR", "Funding"), "miss-" + UUID.randomUUID()).reference();
        jdbc.update("update ledger_entry set posted_at = ? where transaction_id = "
                + "(select id from bank_transaction where reference = ?)", Timestamp.from(when), reference);
        jdbc.update("update bank_transaction set posted_at = ? where reference = ?", Timestamp.from(when), reference);
    }

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    private BigDecimal accrued(UUID id) {
        return accountService.require(id).getAccruedInterest();
    }

    private static BigDecimal days(BigDecimal daily, int count) {
        return daily.multiply(BigDecimal.valueOf(count));
    }

    @Test
    @DisplayName("days the run never reached are accrued when it next does")
    void missedDaysAreAccrued() {
        interestService.accrue(accountId, JAN_1);
        // Down for the 2nd, 3rd and 4th; back on the 5th.
        interestService.accrue(accountId, JAN_1.plusDays(4));

        assertThat(accrued(accountId)).isEqualByComparingTo(days(ON_10K, 5));
        assertThat(accountService.require(accountId).getInterestAccruedThrough()).isEqualTo(JAN_1.plusDays(4));
    }

    @Test
    @DisplayName("each missed day earns on the balance it opened with, not on today's")
    void eachMissedDayEarnsOnItsOwnBalance() {
        interestService.accrue(accountId, JAN_1);
        // Another 10,000 arrives at midday on the 3rd: the 3rd opened at 10,000, the 4th at 20,000.
        deposit(accountId, "10000.00", at("2026-01-03T12:00:00Z"));

        interestService.accrue(accountId, JAN_1.plusDays(5));

        // 1st, 2nd and 3rd on 10,000; 4th, 5th and 6th on 20,000.
        assertThat(accrued(accountId)).isEqualByComparingTo(days(ON_10K, 3).add(days(ON_20K, 3)));
    }

    @Test
    @DisplayName("a missed day the account spent frozen still earns nothing")
    void daysFrozenDuringTheGapEarnNothing() {
        interestService.accrue(accountId, JAN_1);
        // Another 10,000 lands on the 3rd, so the days either side of the freeze earn differently.
        deposit(accountId, "10000.00", at("2026-01-03T06:00:00Z"));
        accountService.changeStatus(accountId, AccountStatus.FROZEN, "Fraud report");
        accountService.changeStatus(accountId, AccountStatus.ACTIVE, null);
        // Frozen at midday on the 2nd, unfrozen at midday on the 4th: the 2nd opened active, the
        // 3rd and 4th opened frozen. A frozen account is never picked up by the run, so these are
        // missed days whatever the run was doing.
        jdbc.update("update account_status_change set changed_at = ? where account_id = ? and to_status = 'FROZEN'",
                Timestamp.from(at("2026-01-02T12:00:00Z")), accountId);
        jdbc.update("update account_status_change set changed_at = ? where account_id = ? and to_status = 'ACTIVE'",
                Timestamp.from(at("2026-01-04T12:00:00Z")), accountId);

        interestService.accrue(accountId, JAN_1.plusDays(5));

        // The 1st and 2nd on 10,000; the 5th and 6th on 20,000. Judging a day by how it ended
        // rather than how it opened would pay the 4th instead of the 2nd -- the same number of
        // days, but one of them on the larger balance.
        assertThat(accrued(accountId)).isEqualByComparingTo(days(ON_10K, 2).add(days(ON_20K, 2)));
    }

    @Test
    @DisplayName("a brand new account is not backdated: it earns from the day it is first accrued")
    void aNewAccountStartsWhereItIsFirstAccrued() {
        interestService.accrue(accountId, JAN_1.plusDays(4));

        assertThat(accrued(accountId)).isEqualByComparingTo(ON_10K);
    }

    @Test
    @DisplayName("catching up does not reopen a day already accrued")
    void anEarlierDayIsStillRefused() {
        interestService.accrue(accountId, JAN_1);
        interestService.accrue(accountId, JAN_1.plusDays(4));

        assertThat(interestService.accrue(accountId, JAN_1.plusDays(2))).isFalse();
        assertThat(interestService.accrue(accountId, JAN_1.plusDays(4))).isFalse();
        assertThat(accrued(accountId)).isEqualByComparingTo(days(ON_10K, 5));
    }
}
