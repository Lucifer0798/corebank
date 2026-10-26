import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useReplay } from "../api/hooks";
import { AdminPage, ReplayCard } from "./AdminPage";

/**
 * The replay used to need a hand-built API call with a token. What this screen has to get right:
 * nothing is sent for a window that makes no sense, the window sent is the one shown, and the
 * result says how much was re-sent.
 */
vi.mock("react-oidc-context", () => ({ useAuth: () => ({ user: { access_token: "" } }) }));
vi.mock("../api/hooks", () => ({ useReplay: vi.fn() }));

const mutate = vi.fn();

function replayState(overrides: Record<string, unknown> = {}) {
  vi.mocked(useReplay).mockReturnValue(
    { mutate, isPending: false, isSuccess: false, error: null, data: undefined, ...overrides } as unknown as ReturnType<typeof useReplay>);
}

beforeEach(() => {
  mutate.mockClear();
  replayState();
});

afterEach(cleanup);

describe("ReplayCard", () => {
  it("sends nothing for a window with no end", () => {
    render(<ReplayCard kind="transactions" />);

    fireEvent.change(screen.getByLabelText(/^From/), { target: { value: "2026-10-01T09:00" } });
    fireEvent.click(screen.getByRole("button", { name: "Replay transactions" }));

    expect(mutate).not.toHaveBeenCalled();
    expect(screen.getByText("Choose both ends of the window.")).toBeTruthy();
  });

  it("replays everything from the epoch when asked to", () => {
    render(<ReplayCard kind="transactions" />);

    fireEvent.click(screen.getByRole("button", { name: "Everything" }));
    fireEvent.click(screen.getByRole("button", { name: "Replay transactions" }));

    expect(mutate).toHaveBeenCalledTimes(1);
    expect(mutate.mock.calls[0][0].since).toBe("1970-01-01T00:00:00.000Z");
  });

  it("says how many events were re-sent", () => {
    // The success line needs the window that was run, which the card records on success.
    mutate.mockImplementation((_window, options) => options?.onSuccess?.());
    const { rerender } = render(<ReplayCard kind="customers" />);
    fireEvent.click(screen.getByRole("button", { name: "Last 24 hours" }));
    fireEvent.click(screen.getByRole("button", { name: "Replay customers" }));

    replayState({ isSuccess: true, data: { eventsEnqueued: 12 } });
    rerender(<ReplayCard kind="customers" />);

    expect(screen.getByText(/Re-sent 12 customer events/)).toBeTruthy();
  });
});

describe("AdminPage", () => {
  it("offers nothing to a non-admin", () => {
    render(<AdminPage />);

    expect(screen.getByText("Admins only")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Replay/ })).toBeNull();
  });
});
