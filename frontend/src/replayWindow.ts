/**
 * The window a replay covers, from what an admin typed.
 *
 * `datetime-local` inputs are wall-clock times with no zone; `new Date(value)` reads them as the
 * browser's local time, which is what the admin meant, and `toISOString()` turns that into the
 * UTC instant the backend wants. The window is [since, until): until is exclusive.
 */
export type ReplayWindow = { since: string; until: string } | { error: string };

export function replayWindow(sinceLocal: string, untilLocal: string): ReplayWindow {
  if (!sinceLocal || !untilLocal) {
    return { error: "Choose both ends of the window." };
  }
  const since = new Date(sinceLocal);
  const until = new Date(untilLocal);
  if (Number.isNaN(since.getTime()) || Number.isNaN(until.getTime())) {
    return { error: "That is not a date and time." };
  }
  if (until.getTime() <= since.getTime()) {
    return { error: "The end of the window must be after its start." };
  }
  return { since: since.toISOString(), until: until.toISOString() };
}

/** A Date as the value a `datetime-local` input shows: local wall-clock time, to the minute. */
export function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * Ready-made windows. "Everything" runs from the epoch to a minute from now, so a posting made
 * while the form was open is still inside the exclusive end -- the replay that #39 and #51 call
 * for is "everything".
 */
export function preset(kind: "day" | "month" | "everything", now: Date = new Date()): { since: string; until: string } {
  const until = new Date(now.getTime() + 60_000);
  const since = kind === "day"
    ? new Date(now.getTime() - 24 * 3600_000)
    : kind === "month"
      ? new Date(now.getTime() - 30 * 24 * 3600_000)
      : new Date(0);
  return { since: toLocalInput(since), until: toLocalInput(until) };
}
