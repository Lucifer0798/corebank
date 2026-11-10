package com.corebank.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebank.account.service.HoldExpiryRunner;
import com.corebank.account.service.HoldService;
import com.corebank.account.service.InterestRunner;
import com.corebank.account.service.InterestService;
import com.corebank.ledger.LedgerCheckRunner;
import com.corebank.ledger.LedgerCheckService;
import com.corebank.schedule.ScheduledTransferRunner;
import com.corebank.schedule.service.ScheduledTransferService;
import com.corebank.schedule.service.ScheduledTransferService.DueOccurrence;
import com.corebank.transaction.dto.TransferRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every background task's failure is counted, under one metric the alert watches, and counted from
 * zero. The runners all swallow a failure so it cannot stop the rest of their batch; these tests
 * are what stop that also making the failure invisible.
 */
class BackgroundFailuresTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private double failures(String task) {
        return meters.get(BackgroundFailures.METRIC).tag("task", task).counter().count();
    }

    @Test
    @DisplayName("every task's failures are counted from zero, before anything has failed")
    void everyTaskStartsAtZero() {
        new InterestRunner(mock(InterestService.class), meters);
        new HoldExpiryRunner(mock(HoldService.class), meters);
        new ScheduledTransferRunner(mock(ScheduledTransferService.class), meters, CLOCK);
        new LedgerCheckRunner(mock(LedgerCheckService.class), meters);

        // A counter made on its first failure is born at 1, and increase() cannot see that.
        for (String task : List.of("interest-accrual", "interest-capitalisation", "hold-expiry",
                "standing-orders", "ledger-check")) {
            assertThat(failures(task)).as(task).isZero();
        }
        for (String outcome : List.of("posted", "failed", "error")) {
            assertThat(meters.get("corebank.scheduled.transfers").tag("outcome", outcome).counter().count())
                    .as(outcome).isZero();
        }
    }

    @Test
    @DisplayName("an account interest could not be accrued on is counted")
    void aFailedAccrualIsCounted() {
        InterestService interest = mock(InterestService.class);
        UUID account = UUID.randomUUID();
        when(interest.today()).thenReturn(TODAY);
        when(interest.findAccountsToAccrue(TODAY, InterestService.FIRST)).thenReturn(List.of(account));
        when(interest.accrue(account, TODAY)).thenThrow(new IllegalStateException("no such ledger"));

        new InterestRunner(interest, meters).run();

        assertThat(failures("interest-accrual")).isEqualTo(1);
        assertThat(failures("interest-capitalisation")).isZero();
    }

    @Test
    @DisplayName("an account interest could not be paid to is counted")
    void aFailedCapitalisationIsCounted() {
        InterestService interest = mock(InterestService.class);
        UUID account = UUID.randomUUID();
        when(interest.today()).thenReturn(TODAY);
        when(interest.findAccountsToCapitalise(TODAY, InterestService.FIRST)).thenReturn(List.of(account));
        when(interest.capitalise(account, TODAY)).thenThrow(new IllegalStateException("no expense account"));

        new InterestRunner(interest, meters).run();

        assertThat(failures("interest-capitalisation")).isEqualTo(1);
        assertThat(failures("interest-accrual")).isZero();
    }

    @Test
    @DisplayName("a hold that could not be expired is counted, and the next one still is")
    void aFailedExpiryIsCounted() {
        HoldService holds = mock(HoldService.class);
        UUID stuck = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        when(holds.findExpired()).thenReturn(List.of(stuck, fine));
        doThrow(new IllegalStateException("row locked")).when(holds).expire(stuck);

        new HoldExpiryRunner(holds, meters).sweep();

        assertThat(failures("hold-expiry")).isEqualTo(1);
        org.mockito.Mockito.verify(holds).expire(fine);
    }

    @Test
    @DisplayName("a standing order the runner could not process is counted; one refused for want of funds is not")
    void onlyTheRunnersOwnFaultsCount() {
        ScheduledTransferService service = mock(ScheduledTransferService.class);
        UUID broken = UUID.randomUUID();
        UUID shortOfFunds = UUID.randomUUID();
        DueOccurrence due = new DueOccurrence(shortOfFunds, TODAY, "sched:" + shortOfFunds + ":" + TODAY,
                new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "INR", "Rent"));
        when(service.findDue(TODAY)).thenReturn(List.of(broken, shortOfFunds));
        when(service.claim(broken, TODAY)).thenThrow(new IllegalStateException("row in a bad state"));
        when(service.claim(shortOfFunds, TODAY)).thenReturn(Optional.of(due));
        when(service.execute(eq(due))).thenThrow(new IllegalStateException("insufficient funds"));

        new ScheduledTransferRunner(service, meters, CLOCK).run();

        assertThat(failures("standing-orders")).isEqualTo(1);
        assertThat(meters.get("corebank.scheduled.transfers").tag("outcome", "failed").counter().count()).isEqualTo(1);
        org.mockito.Mockito.verify(service).markFailure(eq(shortOfFunds), eq(TODAY), any());
    }

    @Test
    @DisplayName("a ledger check that cannot run is counted, rather than looking like a clean one")
    void aLedgerCheckThatCannotRunIsCounted() {
        LedgerCheckService check = mock(LedgerCheckService.class);
        when(check.check()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(new LedgerCheckRunner(check, meters)::run).doesNotThrowAnyException();

        assertThat(failures("ledger-check")).isEqualTo(1);
    }
}
