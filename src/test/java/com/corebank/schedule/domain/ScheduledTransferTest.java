package com.corebank.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The timetable, in isolation. All of this is arithmetic over dates, which makes it both the
 * easiest part of the feature to get subtly wrong and the cheapest to pin exactly -- no database,
 * no clock, no waiting a month to find out.
 */
class ScheduledTransferTest {

    private static final UUID ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static ScheduledTransfer mandate(ScheduleFrequency frequency, LocalDate startsOn, LocalDate endsOn) {
        ScheduledTransfer schedule = new ScheduledTransfer();
        schedule.setId(ID);
        schedule.setSourceAccountId(UUID.randomUUID());
        schedule.setDestinationAccountId(UUID.randomUUID());
        schedule.setAmount(new BigDecimal("750.00"));
        schedule.setCurrency("INR");
        schedule.setFrequency(frequency);
        schedule.setStartsOn(startsOn);
        schedule.setEndsOn(endsOn);
        schedule.schedule();
        return schedule;
    }

    @Test
    @DisplayName("a monthly mandate started on the 31st returns to the 31st after a short month")
    void monthlyDoesNotDriftOffTheEndOfTheMonth() {
        // The bug this exists to prevent: advancing by adding a month to the *previous* due date.
        // 31 Jan + 1 month clamps to 28 Feb, and 28 Feb + 1 month is 28 Mar -- so a rent payment
        // silently moves three days earlier and stays there for the life of the mandate. Measured
        // from the start date instead, February clamps and March recovers.
        ScheduledTransfer schedule = mandate(ScheduleFrequency.MONTHLY, LocalDate.of(2026, 1, 31), null);

        assertThat(schedule.getNextRunOn()).isEqualTo(LocalDate.of(2026, 1, 31));

        schedule.recordSuccess(LocalDate.of(2026, 1, 31));
        assertThat(schedule.getNextRunOn()).isEqualTo(LocalDate.of(2026, 2, 28));

        schedule.recordSuccess(LocalDate.of(2026, 2, 28));
        assertThat(schedule.getNextRunOn())
                .describedAs("March has a 31st, so the mandate goes back to it")
                .isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("daily and weekly advance by their own step")
    void dailyAndWeeklyAdvance() {
        ScheduledTransfer daily = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);
        daily.recordSuccess(LocalDate.of(2026, 5, 1));
        assertThat(daily.getNextRunOn()).isEqualTo(LocalDate.of(2026, 5, 2));

        ScheduledTransfer weekly = mandate(ScheduleFrequency.WEEKLY, LocalDate.of(2026, 5, 1), null);
        weekly.recordSuccess(LocalDate.of(2026, 5, 1));
        assertThat(weekly.getNextRunOn()).isEqualTo(LocalDate.of(2026, 5, 8));
    }

    @Test
    @DisplayName("a one-off mandate completes after its single occurrence")
    void onceCompletesAfterOneRun() {
        ScheduledTransfer schedule = mandate(ScheduleFrequency.ONCE, LocalDate.of(2026, 5, 1), null);

        schedule.recordSuccess(LocalDate.of(2026, 5, 1));

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.COMPLETED);
        assertThat(schedule.getNextRunOn()).isNull();
        assertThat(schedule.getRunsCompleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("an end date stops the mandate rather than truncating mid-occurrence")
    void endDateCompletesTheMandate() {
        ScheduledTransfer schedule = mandate(
                ScheduleFrequency.WEEKLY, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10));

        schedule.recordSuccess(LocalDate.of(2026, 5, 1));
        assertThat(schedule.getNextRunOn()).isEqualTo(LocalDate.of(2026, 5, 8));

        // The next one would be 15 May, past the window.
        schedule.recordSuccess(LocalDate.of(2026, 5, 8));
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.COMPLETED);
        assertThat(schedule.getNextRunOn()).isNull();
    }

    @Test
    @DisplayName("a window containing no occurrence at all is refused outright")
    void aWindowWithNoOccurrenceIsRefused() {
        ScheduledTransfer schedule = new ScheduledTransfer();
        schedule.setFrequency(ScheduleFrequency.MONTHLY);
        schedule.setStartsOn(LocalDate.of(2026, 5, 10));
        schedule.setEndsOn(LocalDate.of(2026, 5, 1));

        // Otherwise this would persist as ACTIVE with no next date -- permanently live, never due.
        assertThatThrownBy(schedule::schedule)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("SCHEDULE_NEVER_RUNS");
    }

    @Test
    @DisplayName("a failed occurrence is skipped, not retried in place")
    void failureSkipsTheOccurrence() {
        ScheduledTransfer schedule = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);

        schedule.recordFailure(LocalDate.of(2026, 5, 1), "INSUFFICIENT_FUNDS", 3);

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);
        assertThat(schedule.getNextRunOn())
                .describedAs("tomorrow, not today again -- retrying in place would hammer a short account")
                .isEqualTo(LocalDate.of(2026, 5, 2));
        assertThat(schedule.getConsecutiveFailures()).isEqualTo(1);
        assertThat(schedule.getRunsCompleted()).isZero();
        assertThat(schedule.getLastError()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("enough consecutive failures suspend the mandate")
    void repeatedFailuresSuspend() {
        ScheduledTransfer schedule = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);

        schedule.recordFailure(LocalDate.of(2026, 5, 1), "INSUFFICIENT_FUNDS", 3);
        schedule.recordFailure(LocalDate.of(2026, 5, 2), "INSUFFICIENT_FUNDS", 3);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);

        schedule.recordFailure(LocalDate.of(2026, 5, 3), "INSUFFICIENT_FUNDS", 3);

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SUSPENDED);
        assertThat(schedule.getNextRunOn())
                .describedAs("a suspended mandate must not stay due, or the runner would keep picking it up")
                .isNull();
    }

    @Test
    @DisplayName("a success resets the failure count")
    void successClearsTheFailureRun() {
        // "Consecutive" has to mean consecutive: a mandate that fails on the 1st of every month
        // for a year but succeeds in between is not one to suspend.
        ScheduledTransfer schedule = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);

        schedule.recordFailure(LocalDate.of(2026, 5, 1), "INSUFFICIENT_FUNDS", 3);
        schedule.recordFailure(LocalDate.of(2026, 5, 2), "INSUFFICIENT_FUNDS", 3);
        schedule.recordSuccess(LocalDate.of(2026, 5, 3));
        schedule.recordFailure(LocalDate.of(2026, 5, 4), "INSUFFICIENT_FUNDS", 3);

        assertThat(schedule.getConsecutiveFailures()).isEqualTo(1);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);
        assertThat(schedule.getLastError()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("a one-off that fails is suspended, not completed")
    void aFailedOneOffIsSuspended() {
        // The distinction matters to whoever reads the list: COMPLETED means the money moved.
        ScheduledTransfer schedule = mandate(ScheduleFrequency.ONCE, LocalDate.of(2026, 5, 1), null);

        schedule.recordFailure(LocalDate.of(2026, 5, 1), "INSUFFICIENT_FUNDS", 3);

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SUSPENDED);
        assertThat(schedule.getRunsCompleted()).isZero();
    }

    @Test
    @DisplayName("every attempt at one occurrence derives the same idempotency key")
    void theIdempotencyKeyIsDerivedFromTheOccurrence() {
        ScheduledTransfer schedule = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);

        String key = schedule.idempotencyKeyFor(LocalDate.of(2026, 5, 1));

        // This is what makes a crash between posting and bookkeeping safe: the re-run presents the
        // same key and replays rather than posting twice.
        assertThat(key).isEqualTo("sched:" + ID + ":2026-05-01");
        assertThat(schedule.idempotencyKeyFor(LocalDate.of(2026, 5, 1))).isEqualTo(key);
        assertThat(schedule.idempotencyKeyFor(LocalDate.of(2026, 5, 2))).isNotEqualTo(key);
        assertThat(key.length())
                .describedAs("the API caps Idempotency-Key at 80 characters")
                .isLessThanOrEqualTo(80);
    }

    @Test
    @DisplayName("cancelling stops it, and cancelling twice is refused")
    void cancellingIsTerminal() {
        ScheduledTransfer schedule = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);

        schedule.cancel();

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.CANCELLED);
        assertThat(schedule.getNextRunOn()).isNull();
        assertThatThrownBy(schedule::cancel)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getCode())
                .isEqualTo("SCHEDULE_NOT_ACTIVE");
    }

    @Test
    @DisplayName("only an active mandate with a date in reach is due")
    void dueness() {
        ScheduledTransfer schedule = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 10), null);

        assertThat(schedule.isDueOn(LocalDate.of(2026, 5, 9))).isFalse();
        assertThat(schedule.isDueOn(LocalDate.of(2026, 5, 10))).isTrue();
        // Catch-up after an outage: still due, and the runner works through one occurrence a tick.
        assertThat(schedule.isDueOn(LocalDate.of(2026, 5, 14))).isTrue();

        schedule.cancel();
        assertThat(schedule.isDueOn(LocalDate.of(2026, 5, 14))).isFalse();
    }
}
