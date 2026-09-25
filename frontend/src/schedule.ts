import type { ScheduledTransfer } from "./api/types";

/**
 * What a standing instruction needs said about it, beyond its status pill.
 *
 * <p>The backend already tracks why a mandate is struggling -- `consecutiveFailures` and
 * `lastError` are on every response -- but until now none of it was visible outside a curl call.
 * A mandate that has quietly failed twice looks identical to a healthy one in a list, and the
 * one that gave up looks like any other stopped row. These are the two cases worth interrupting
 * someone about, and the only two this returns anything for.
 */
export type ScheduleAttention =
  | { level: "none" }
  | { level: "warning" | "stopped"; message: string };

export function scheduleAttention(schedule: ScheduledTransfer): ScheduleAttention {
  if (schedule.status === "SUSPENDED") {
    return {
      level: "stopped",
      message:
        `Stopped after ${plural(schedule.consecutiveFailures, "failed attempt")}. ` +
        (schedule.lastError ?? "No reason was recorded.") +
        " Set up a replacement once the problem is fixed -- a suspended instruction cannot be restarted.",
    };
  }
  if (schedule.status === "ACTIVE" && schedule.consecutiveFailures > 0) {
    return {
      level: "warning",
      message:
        `${plural(schedule.consecutiveFailures, "attempt")} failed since the last success. ` +
        (schedule.lastError ?? "No reason was recorded."),
    };
  }
  return { level: "none" };
}

/** Only a live mandate can be stopped; the backend refuses the rest with SCHEDULE_NOT_ACTIVE. */
export function canCancel(schedule: ScheduledTransfer): boolean {
  return schedule.status === "ACTIVE";
}

/**
 * Which way the money moves, seen from the account being looked at. The same mandate appears on
 * both accounts it names, and "-750" on the payer's page and "+750" on the payee's is the only
 * thing that distinguishes them.
 */
export function directionFor(schedule: ScheduledTransfer, accountId: string): "out" | "in" {
  return schedule.sourceAccountId === accountId ? "out" : "in";
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}
