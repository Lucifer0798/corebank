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

        schedule.recordFailure(LocalDate.of(2026, 5, 1), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);

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

        schedule.recordFailure(LocalDate.of(2026, 5, 1), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);
        schedule.recordFailure(LocalDate.of(2026, 5, 2), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);

        schedule.recordFailure(LocalDate.of(2026, 5, 3), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);

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

        schedule.recordFailure(LocalDate.of(2026, 5, 1), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);
        schedule.recordFailure(LocalDate.of(2026, 5, 2), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);
        schedule.recordSuccess(LocalDate.of(2026, 5, 3));
        schedule.recordFailure(LocalDate.of(2026, 5, 4), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);

        assertThat(schedule.getConsecutiveFailures()).isEqualTo(1);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);
        assertThat(schedule.getLastError()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("a one-off that fails is suspended, not completed")
    void aFailedOneOffIsSuspended() {
        // The distinction matters to whoever reads the list: COMPLETED means the money moved.
        ScheduledTransfer schedule = mandate(ScheduleFrequency.ONCE, LocalDate.of(2026, 5, 1), null);

        schedule.recordFailure(LocalDate.of(2026, 5, 1), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SUSPENDED);
        assertThat(schedule.getRunsCompleted()).isZero();
    }

    /** Fails {@code times} consecutive occurrences, starting with the one currently due. */
    private static void failRepeatedly(ScheduledTransfer schedule, int times) {
        for (int i = 0; i < times; i++) {
            schedule.recordFailure(schedule.getNextRunOn(), "INSUFFICIENT_FUNDS", "INSUFFICIENT_FUNDS", 3);
        }
    }

    private static String codeOf(Throwable ex) {
        return ((BusinessRuleException) ex).getCode();
    }

    @Test
    @DisplayName("resuming keeps the original timetable -- a payment on the 31st stays on the 31st")
    void resumeKeepsTheAnchorDate() {
        // The reason resume exists rather than "set up a new one": a replacement created on the 5th
        // would pay on the 5th from then on.
        ScheduledTransfer schedule = mandate(ScheduleFrequency.MONTHLY, LocalDate.of(2026, 1, 31), null);
        failRepeatedly(schedule, 3); // 31 Jan, 28 Feb, 31 Mar
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SUSPENDED);

        schedule.resume(LocalDate.of(2026, 5, 5));

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.ACTIVE);
        assertThat(schedule.getNextRunOn())
                .describedAs("the next 31st-anchored date after today; April's was missed while stopped and is not paid")
                .isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(schedule.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("resuming on the day it was suspended does not retry the occurrence that just failed")
    void resumeNeverRetriesTheFailedOccurrence() {
        // The final failure does not advance the index, so the first candidate on or after today is
        // the date that was just refused. Picking it would re-run that payment on the next tick.
        ScheduledTransfer schedule = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);
        failRepeatedly(schedule, 3); // 1, 2, 3 May

        schedule.resume(LocalDate.of(2026, 5, 3));

        assertThat(schedule.getNextRunOn()).isEqualTo(LocalDate.of(2026, 5, 4));
    }

    @Test
    @DisplayName("a resumed instruction gets a full allowance of failures again")
    void resumeResetsTheFailureRun() {
        ScheduledTransfer schedule = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);
        failRepeatedly(schedule, 3);
        schedule.resume(LocalDate.of(2026, 5, 10));

        failRepeatedly(schedule, 1);

        assertThat(schedule.getStatus())
                .describedAs("one failure after a resume is one, not a fourth in a row")
                .isEqualTo(ScheduleStatus.ACTIVE);
    }

    @Test
    @DisplayName("a one-off, or a schedule past its end date, has nothing to resume")
    void resumeIsRefusedWhenNothingIsLeft() {
        ScheduledTransfer oneOff = mandate(ScheduleFrequency.ONCE, LocalDate.of(2026, 5, 1), null);
        failRepeatedly(oneOff, 1);
        assertThatThrownBy(() -> oneOff.resume(LocalDate.of(2026, 5, 2)))
                .extracting(ScheduledTransferTest::codeOf).isEqualTo("SCHEDULE_HAS_NO_RUNS_LEFT");

        ScheduledTransfer ended = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10));
        failRepeatedly(ended, 3);
        assertThatThrownBy(() -> ended.resume(LocalDate.of(2026, 5, 11)))
                .extracting(ScheduledTransferTest::codeOf).isEqualTo("SCHEDULE_HAS_NO_RUNS_LEFT");
        assertThat(ended.getStatus())
                .describedAs("a refused resume leaves the instruction exactly as it was")
                .isEqualTo(ScheduleStatus.SUSPENDED);
    }

    @Test
    @DisplayName("only a suspended instruction can be resumed")
    void resumeIsOnlyForSuspended() {
        ScheduledTransfer active = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);
        assertThatThrownBy(() -> active.resume(LocalDate.of(2026, 5, 1)))
                .extracting(ScheduledTransferTest::codeOf).isEqualTo("SCHEDULE_NOT_SUSPENDED");

        ScheduledTransfer cancelled = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);
        cancelled.cancel();
        assertThatThrownBy(() -> cancelled.resume(LocalDate.of(2026, 5, 1)))
                .describedAs("a customer's cancellation is not the bank's to undo")
                .extracting(ScheduledTransferTest::codeOf).isEqualTo("SCHEDULE_NOT_SUSPENDED");
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
    @DisplayName("a suspended mandate can be cancelled; a completed one cannot")
    void cancellingASuspendedMandate() {
        // Suspended can be resumed, so cancelling is the only way to retire one for good -- and the
        // only way to close an account it names.
        ScheduledTransfer suspended = mandate(ScheduleFrequency.DAILY, LocalDate.of(2026, 5, 1), null);
        failRepeatedly(suspended, 3);
        assertThat(suspended.getStatus()).isEqualTo(ScheduleStatus.SUSPENDED);

        suspended.cancel();
        assertThat(suspended.getStatus()).isEqualTo(ScheduleStatus.CANCELLED);
        assertThatThrownBy(() -> suspended.resume(LocalDate.of(2026, 5, 10)))
                .describedAs("and once cancelled it stays cancelled")
                .extracting(ScheduledTransferTest::codeOf).isEqualTo("SCHEDULE_NOT_SUSPENDED");

        ScheduledTransfer completed = mandate(ScheduleFrequency.ONCE, LocalDate.of(2026, 5, 1), null);
        completed.recordSuccess(LocalDate.of(2026, 5, 1));
        assertThatThrownBy(completed::cancel)
                .describedAs("COMPLETED means the money moved; there is nothing left to stop")
                .extracting(ScheduledTransferTest::codeOf).isEqualTo("SCHEDULE_NOT_ACTIVE");
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
