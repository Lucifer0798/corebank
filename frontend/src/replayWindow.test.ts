import { describe, expect, it } from "vitest";
import { preset, replayWindow, toLocalInput } from "./replayWindow";

/**
 * The window an admin types becomes the UTC instants the backend replays between. Written to hold
 * in whatever zone the test machine is in: every check round-trips through the local reading.
 */
describe("replayWindow", () => {
  it("turns two local times into UTC instants", () => {
    const since = new Date(2026, 9, 1, 9, 0);
    const until = new Date(2026, 9, 2, 9, 0);

    const window = replayWindow(toLocalInput(since), toLocalInput(until));

    expect(window).toEqual({ since: since.toISOString(), until: until.toISOString() });
  });

  it("refuses a window that is empty, backwards, or missing an end", () => {
    // The backend would refuse these too (INVALID_REPLAY_WINDOW); saying so here saves the round trip.
    expect(replayWindow("", "2026-10-02T09:00")).toEqual({ error: "Choose both ends of the window." });
    expect(replayWindow("2026-10-02T09:00", "2026-10-02T09:00"))
      .toEqual({ error: "The end of the window must be after its start." });
    expect(replayWindow("2026-10-02T09:00", "2026-10-01T09:00"))
      .toEqual({ error: "The end of the window must be after its start." });
    expect(replayWindow("nonsense", "2026-10-01T09:00")).toEqual({ error: "That is not a date and time." });
  });
});

describe("preset", () => {
  const now = new Date(2026, 9, 26, 12, 30);

  it("'everything' runs from the epoch to just after now, so nothing posted so far is left out", () => {
    const window = replayWindow(preset("everything", now).since, preset("everything", now).until);
    if ("error" in window) throw new Error(window.error);

    expect(window.since).toBe("1970-01-01T00:00:00.000Z");
    // The end is exclusive: a posting made at `now` must still be inside it.
    expect(new Date(window.until).getTime()).toBeGreaterThan(now.getTime());
  });

  it("'day' covers the last 24 hours", () => {
    const window = replayWindow(preset("day", now).since, preset("day", now).until);
    if ("error" in window) throw new Error(window.error);

    const span = new Date(window.until).getTime() - new Date(window.since).getTime();
    expect(span).toBe(24 * 3600_000 + 60_000);
  });
});
