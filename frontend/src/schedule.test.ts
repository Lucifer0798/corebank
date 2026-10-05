import { describe, expect, it } from "vitest";
import type { ScheduledTransfer } from "./api/types";
import { canCancel, canResume, directionFor, scheduleAttention } from "./schedule";

/**
 * A standing instruction fails quietly by design -- the runner skips the occurrence, logs it, and
 * carries on. `consecutiveFailures` and `lastError` are the only trace of that anywhere a person
 * will look, so what this module decides to surface is the difference between a mandate silently
 * dying and somebody noticing in time to fix it.
 */
const ACCOUNT = "11111111-1111-1111-1111-111111111111";
const OTHER = "22222222-2222-2222-2222-222222222222";

function schedule(overrides: Partial<ScheduledTransfer> = {}): ScheduledTransfer {
  return {
    id: "aaaaaaaa-0000-4000-8000-000000000001",
    sourceAccountId: ACCOUNT,
    destinationAccountId: OTHER,
    amount: 750,
    currency: "INR",
    description: "Rent",
    frequency: "MONTHLY",
    startsOn: "2026-10-01",
    endsOn: null,
    status: "ACTIVE",
    nextRunOn: "2026-11-01",
    runsCompleted: 1,
    consecutiveFailures: 0,
    lastRunOn: "2026-10-01",
    lastError: null,
    ...overrides,
  };
}

describe("scheduleAttention", () => {
  it("says nothing about a healthy mandate", () => {
    expect(scheduleAttention(schedule())).toEqual({ level: "none" });
  });

  it("says nothing about one that finished or was cancelled", () => {
    // Neither is a problem, and flagging them would bury the two that are.
    expect(scheduleAttention(schedule({ status: "COMPLETED", nextRunOn: null })).level).toBe("none");
    expect(scheduleAttention(schedule({ status: "CANCELLED", nextRunOn: null })).level).toBe("none");
  });

  it("warns about a live mandate that has started failing", () => {
    const attention = scheduleAttention(schedule({
      consecutiveFailures: 2,
      lastError: "There was not enough money in the account (750.00 INR was due).",
    }));

    expect(attention.level).toBe("warning");
    expect(attention.level !== "none" && attention.message).toContain("2 attempts failed");
    // The reason, verbatim from the backend. Customer-safe since it is shown to the payee as
    // well: the cause and the amount due, never the payer's balance or account number.
    expect(attention.level !== "none" && attention.message).toContain("750.00 INR was due");
  });

  it("reports a suspended mandate as stopped, and says it can be resumed", () => {
    const attention = scheduleAttention(schedule({
      status: "SUSPENDED",
      nextRunOn: null,
      consecutiveFailures: 3,
      lastError: "There was not enough money in the account (750.00 INR was due).",
    }));

    expect(attention.level).toBe("stopped");
    expect(attention.level !== "none" && attention.message).toContain("3 failed attempts");
    // Staff can resume it now, and saying so is what stops a teller recreating the instruction --
    // which would move its timetable to whatever day they happened to do it.
    expect(attention.level !== "none" && attention.message).toContain("resume");
  });

  it("gets the singular right", () => {
    const attention = scheduleAttention(schedule({ consecutiveFailures: 1, lastError: "nope" }));
    expect(attention.level !== "none" && attention.message).toContain("1 attempt failed");
  });

  it("still explains itself when no reason was recorded", () => {
    // lastError is nullable on the API, so the message has to survive it being absent rather
    // than rendering "undefined" at a teller.
    const attention = scheduleAttention(schedule({ consecutiveFailures: 1, lastError: null }));

    expect(attention.level !== "none" && attention.message).toContain("No reason was recorded");
    expect(attention.level !== "none" && attention.message).not.toContain("null");
  });
});

describe("canCancel", () => {
  it("allows cancelling only a live mandate", () => {
    // The backend refuses the rest with SCHEDULE_NOT_ACTIVE, so offering the button would only
    // produce an error the user could not have avoided.
    expect(canCancel(schedule())).toBe(true);
    expect(canCancel(schedule({ status: "SUSPENDED" }))).toBe(false);
    expect(canCancel(schedule({ status: "COMPLETED" }))).toBe(false);
    expect(canCancel(schedule({ status: "CANCELLED" }))).toBe(false);
  });
});

describe("canResume", () => {
  it("offers resuming only a suspended mandate", () => {
    expect(canResume(schedule({ status: "SUSPENDED", nextRunOn: null }))).toBe(true);
    expect(canResume(schedule())).toBe(false);
    // A cancellation was somebody's decision; a completion means the money moved. Neither is the
    // bank's to restart.
    expect(canResume(schedule({ status: "CANCELLED", nextRunOn: null }))).toBe(false);
    expect(canResume(schedule({ status: "COMPLETED", nextRunOn: null }))).toBe(false);
  });
});

describe("directionFor", () => {
  it("reads the same mandate as outgoing or incoming depending on whose page it is", () => {
    // One row, two accounts, and the sign is the only thing telling them apart.
    expect(directionFor(schedule(), ACCOUNT)).toBe("out");
    expect(directionFor(schedule(), OTHER)).toBe("in");
  });
});
