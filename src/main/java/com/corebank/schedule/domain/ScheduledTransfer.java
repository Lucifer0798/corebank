package com.corebank.schedule.domain;

import com.corebank.common.domain.AuditableEntity;
import com.corebank.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * A standing instruction to move money on a schedule -- the first thing in this system that acts
 * without anyone having made a request.
 *
 * <p>The mandate owns its own timetable and the rules for advancing it; the runner only asks it
 * what happened and when it is next due. {@code occurrenceIndex} counts occurrences consumed,
 * whether they succeeded or were skipped, and every due date is derived from it rather than from
 * the previous due date -- see {@link ScheduleFrequency#occurrence}.
 *
 * <p>Accounts are held as ids rather than as {@code @ManyToOne} associations on purpose. The
 * runner hands them straight to {@code TransactionService.transfer}, which loads and row-locks
 * both accounts itself in a deadlock-safe order; mapping them here would invite a second,
 * unlocked copy of the same account into the same transaction.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "scheduled_transfer")
public class ScheduledTransfer extends AuditableEntity {

    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, updatable = false)
    private UUID destinationAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "description", length = 255, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20, updatable = false)
    private ScheduleFrequency frequency;

    @Column(name = "starts_on", nullable = false, updatable = false)
    private LocalDate startsOn;

    /** Inclusive. Null means "until cancelled". */
    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScheduleStatus status = ScheduleStatus.ACTIVE;

    /** The date the next occurrence falls due, or null once nothing further is due. */
    @Column(name = "next_run_on")
    private LocalDate nextRunOn;

    /** How many occurrences have been consumed, successfully or not. Drives every due date. */
    @Column(name = "occurrence_index", nullable = false)
    private int occurrenceIndex;

    @Column(name = "runs_completed", nullable = false)
    private int runsCompleted;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_run_on")
    private LocalDate lastRunOn;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * The key every attempt at one occurrence shares, so a retry -- after a crash mid-run, or a
     * second instance that raced for the same row -- replays the original posting instead of
     * moving the money twice. Derived rather than random for exactly that reason: a fresh key per
     * attempt would make {@code IdempotencyService} treat the retry as new work.
     *
     * <p>Comfortably inside the 80 characters the API allows: 6 + 36 + 1 + 10.
     */
    public String idempotencyKeyFor(LocalDate dueOn) {
        return "sched:" + id + ":" + dueOn;
    }

    /** Places the first occurrence. Called once, at creation. */
    public void schedule() {
        this.occurrenceIndex = 0;
        this.nextRunOn = withinWindow(frequency.occurrence(startsOn, 0));
        if (nextRunOn == null) {
            // An end date before the first occurrence would otherwise create a mandate that is
            // active forever and never due.
            throw new BusinessRuleException("SCHEDULE_NEVER_RUNS",
                    "This schedule has no occurrence on or before its end date");
        }
    }

    /** The money moved. Advance, and finish if that was the last occurrence. */
    public void recordSuccess(LocalDate ranOn) {
        this.runsCompleted++;
        this.consecutiveFailures = 0;
        this.lastError = null;
        this.lastRunOn = ranOn;
        advance(ScheduleStatus.COMPLETED);
    }

    /**
     * The transfer was refused. The occurrence is skipped rather than retried: the runner polls
     * often enough that retrying in place would hammer a short account for the rest of the day,
     * and an instruction that has failed {@code maxConsecutiveFailures} times running is one
     * somebody needs to look at rather than one to keep attempting quietly.
     *
     * <p>Running out of occurrences after a failure suspends rather than completes -- a mandate
     * whose last act was to fail has not finished its job.
     */
    public void recordFailure(LocalDate attemptedOn, String error, int maxConsecutiveFailures) {
        this.consecutiveFailures++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.lastRunOn = attemptedOn;

        if (consecutiveFailures >= maxConsecutiveFailures) {
            this.status = ScheduleStatus.SUSPENDED;
            this.nextRunOn = null;
            return;
        }
        advance(ScheduleStatus.SUSPENDED);
    }

    public void cancel() {
        if (status.isTerminal()) {
            throw new BusinessRuleException("SCHEDULE_NOT_ACTIVE",
                    "This schedule is already " + status.name().toLowerCase());
        }
        this.status = ScheduleStatus.CANCELLED;
        this.nextRunOn = null;
    }

    /** True when this mandate is due on the given date and nothing has stopped it. */
    public boolean isDueOn(LocalDate date) {
        return status == ScheduleStatus.ACTIVE && nextRunOn != null && !nextRunOn.isAfter(date);
    }

    private void advance(ScheduleStatus whenExhausted) {
        this.occurrenceIndex++;
        LocalDate next = withinWindow(frequency.occurrence(startsOn, occurrenceIndex));
        if (next == null) {
            this.status = whenExhausted;
            this.nextRunOn = null;
        } else {
            this.nextRunOn = next;
        }
    }

    /** A candidate date, or null when it falls past the mandate's end. */
    private LocalDate withinWindow(LocalDate candidate) {
        if (candidate == null) {
            return null;
        }
        return endsOn != null && candidate.isAfter(endsOn) ? null : candidate;
    }
}
