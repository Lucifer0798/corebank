package com.corebank.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.domain.AccountType;
import com.corebank.account.dto.OpenAccountRequest;
import com.corebank.account.service.AccountService;
import com.corebank.config.TestDeciders;
import com.corebank.customer.domain.KycStatus;
import com.corebank.customer.dto.CreateCustomerRequest;
import com.corebank.customer.service.CustomerService;
import com.corebank.idempotency.IdempotencyService;
import com.corebank.schedule.ScheduledTransferRunner;
import com.corebank.schedule.domain.ScheduleFrequency;
import com.corebank.schedule.domain.ScheduleStatus;
import com.corebank.schedule.dto.CreateScheduledTransferRequest;
import com.corebank.schedule.dto.ScheduledTransferResponse;
import com.corebank.schedule.service.ScheduledTransferService.DueOccurrence;
import com.corebank.transaction.dto.AmountRequest;
import com.corebank.transaction.service.TransactionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A standing order whose idempotency claim was left behind by an attempt that never finished.
 *
 * <p>Its key is derived from the mandate and the due date, so the next tick reuses it. That tick used
 * to be answered 409 {@code REQUEST_IN_PROGRESS}, which the runner took for a refused payment: the
 * occurrence was skipped, counted towards suspension, and the payer told it had not gone out, when
 * it may well have.
 */
@SpringBootTest
class StuckStandingOrderClaimTest {

    @Autowired private ScheduledTransferService scheduledTransfers;
    @Autowired private IdempotencyService idempotency;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now(Clock.systemUTC());
    private UUID destination;
    private ScheduledTransferResponse mandate;

    @BeforeEach
    void setUp() {
        UUID customer = customerService.create(new CreateCustomerRequest("Stuck", "Payer",
                "stuck." + UUID.randomUUID() + "@example.com", null, LocalDate.of(1990, 1, 1))).id();
        customerService.updateKyc(customer, KycStatus.VERIFIED, null, TestDeciders.STAFF);
        UUID source = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        destination = accountService.open(new OpenAccountRequest(customer, AccountType.SAVINGS, "INR", BigDecimal.ZERO)).id();
        transactionService.deposit(source, new AmountRequest(new BigDecimal("1000.00"), "INR", "Funding"),
                "stuck-fund-" + UUID.randomUUID());
        mandate = scheduledTransfers.create(new CreateScheduledTransferRequest(source, destination,
                new BigDecimal("100.00"), "INR", "Rent", ScheduleFrequency.MONTHLY, today, null));
    }

    @AfterEach
    void cancel() {
        if (scheduledTransfers.get(mandate.id()).status() == ScheduleStatus.ACTIVE) {
            scheduledTransfers.cancel(mandate.id(), null);
        }
    }

    private ScheduledTransferRunner runner() {
        return new ScheduledTransferRunner(scheduledTransfers, new SimpleMeterRegistry(),
                Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a claim left by an attempt that never finished: not a missed payment, and paid once it is abandoned")
    void aStuckClaimIsNotAMissedPayment() {
        // The occurrence exactly as the runner will build it, so the claim's request matches.
        DueOccurrence occurrence = scheduledTransfers.claim(mandate.id(), today).orElseThrow();
        jdbc.update("""
                insert into idempotency_record (id, scope, idempotency_key, request_hash, status, created_at, claim_token)
                values (?, 'scheduled-transfer', ?, ?, 'IN_PROGRESS', ?, ?)
                """, UUID.randomUUID(), occurrence.idempotencyKey(), idempotency.requestHash(occurrence.request()),
                Timestamp.from(Instant.now().minusSeconds(5)), UUID.randomUUID());

        // While the claim is recent, the tick leaves the occurrence alone: still due, nothing recorded.
        runner().run();

        ScheduledTransferResponse untouched = scheduledTransfers.get(mandate.id());
        assertThat(untouched.runsCompleted()).isZero();
        assertThat(untouched.consecutiveFailures()).isZero();
        assertThat(untouched.nextRunOn()).isEqualTo(today);
        assertThat(accountService.get(destination).balance()).isEqualByComparingTo("0.00");

        // Once it is abandoned, the next tick takes it over and pays.
        jdbc.update("update idempotency_record set created_at = ? where scope = 'scheduled-transfer' and idempotency_key = ?",
                Timestamp.from(Instant.now().minusSeconds(300)), occurrence.idempotencyKey());
        runner().run();

        ScheduledTransferResponse paid = scheduledTransfers.get(mandate.id());
        assertThat(paid.runsCompleted()).isEqualTo(1);
        assertThat(paid.consecutiveFailures()).isZero();
        assertThat(accountService.get(destination).balance()).isEqualByComparingTo("100.00");
    }
}
