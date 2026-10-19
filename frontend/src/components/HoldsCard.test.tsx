import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useCaptureHold, useHolds, useReleaseHold } from "../api/hooks";
import type { Account, Hold, PagedResponse } from "../api/types";
import { HoldsCard, captureAmount, canSettle } from "./HoldsCard";

/**
 * Who sees what, and what a capture sends. The rules -- what may be captured, for how much -- are
 * the backend's; this card only has to offer the right controls and pass the amount through.
 */
vi.mock("../api/hooks", () => ({ useHolds: vi.fn(), useCaptureHold: vi.fn(), useReleaseHold: vi.fn() }));

const ACCOUNT_ID = "11111111-1111-1111-1111-111111111111";
const captureMutate = vi.fn();
const releaseMutate = vi.fn();

function account(): Account {
  return {
    id: ACCOUNT_ID, accountNumber: "100100000001", customerId: "cccccccc-0000-4000-8000-000000000001",
    accountType: "SAVINGS", status: "ACTIVE", currency: "INR", balance: 5000, availableBalance: 500,
    overdraftLimit: 0, openedAt: "2026-01-01T00:00:00Z",
  } as Account;
}

function hold(overrides: Partial<Hold> = {}): Hold {
  return {
    id: "hhhhhhhh-0000-4000-8000-000000000001",
    reference: "HLD-20261019-0001",
    accountId: ACCOUNT_ID,
    amount: 4500,
    currency: "INR",
    description: "Hotel authorisation, Taj Bengaluru",
    status: "ACTIVE",
    placedAt: "2026-10-19T09:00:00Z",
    expiresAt: "2026-10-26T09:00:00Z",
    settledAt: null,
    capturedTransactionReference: null,
    captureReversalReference: null,
    placedBy: { subject: "teller-subject", name: "anil" },
    settledBy: null,
    ...overrides,
  };
}

function listing(content: Hold[]) {
  vi.mocked(useHolds).mockReturnValue({
    data: {
      content,
      page: 0,
      size: 10,
      totalElements: content.length,
      totalPages: 1,
      last: true,
    } satisfies PagedResponse<Hold>,
    error: null,
  } as unknown as ReturnType<typeof useHolds>);
}

beforeEach(() => {
  captureMutate.mockClear();
  releaseMutate.mockClear();
  vi.mocked(useCaptureHold).mockReturnValue({ mutate: captureMutate, isPending: false, error: null } as unknown as ReturnType<typeof useCaptureHold>);
  vi.mocked(useReleaseHold).mockReturnValue({ mutate: releaseMutate, isPending: false, error: null } as unknown as ReturnType<typeof useReleaseHold>);
});

afterEach(cleanup);

describe("HoldsCard", () => {
  it("shows a customer what is on hold and until when, with no controls and no staff names", () => {
    listing([hold()]);
    render(<HoldsCard account={account()} staff={false} />);

    expect(screen.getByText("Hotel authorisation, Taj Bengaluru")).toBeTruthy();
    expect(screen.getByText(/4,500/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Capture" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Release" })).toBeNull();
    expect(screen.queryByText(/Placed by/)).toBeNull();
  });

  it("offers staff Capture and Release on an outstanding hold only", () => {
    listing([
      hold(),
      hold({ id: "h2", reference: "HLD-2", status: "CAPTURED", settledBy: { subject: "s", name: "priya" } }),
      hold({ id: "h3", reference: "HLD-3", status: "EXPIRED", settledBy: { subject: "system:hold-expiry", name: "hold-expiry" } }),
    ]);
    render(<HoldsCard account={account()} staff />);

    expect(screen.getAllByRole("button", { name: "Capture" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Release" })).toHaveLength(1);
    expect(screen.getAllByText("Placed by anil")).toHaveLength(3);
    expect(screen.getByText("Settled by priya")).toBeTruthy();
    expect(screen.getByText("Settled by hold-expiry")).toBeTruthy();
  });

  it("captures the full amount when none is typed, and the typed amount otherwise", () => {
    listing([hold()]);
    render(<HoldsCard account={account()} staff />);

    fireEvent.click(screen.getByRole("button", { name: "Capture" }));
    expect(captureMutate).toHaveBeenLastCalledWith({ reference: "HLD-20261019-0001", amount: undefined });

    fireEvent.change(screen.getByLabelText(/Capture amount/), { target: { value: "3999.50" } });
    fireEvent.click(screen.getByRole("button", { name: "Capture" }));
    expect(captureMutate).toHaveBeenLastCalledWith({ reference: "HLD-20261019-0001", amount: 3999.5 });
  });

  it("will not capture an amount that is not one", () => {
    listing([hold()]);
    render(<HoldsCard account={account()} staff />);

    fireEvent.change(screen.getByLabelText(/Capture amount/), { target: { value: "abc" } });
    expect((screen.getByRole("button", { name: "Capture" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("releases by reference", () => {
    listing([hold()]);
    render(<HoldsCard account={account()} staff />);

    fireEvent.click(screen.getByRole("button", { name: "Release" }));
    expect(releaseMutate).toHaveBeenCalledWith("HLD-20261019-0001");
  });

  it("says so when there are no holds", () => {
    listing([]);
    render(<HoldsCard account={account()} staff={false} />);

    expect(screen.getByText("No holds on this account.")).toBeTruthy();
  });
});

describe("captureAmount", () => {
  it("reads blank as the full amount, and refuses anything that is not a positive number", () => {
    expect(captureAmount("")).toBeUndefined();
    expect(captureAmount("   ")).toBeUndefined();
    expect(captureAmount("250")).toBe(250);
    expect(captureAmount("0")).toBeNull();
    expect(captureAmount("-5")).toBeNull();
    expect(captureAmount("abc")).toBeNull();
  });
});

describe("canSettle", () => {
  it("allows settling only an outstanding hold", () => {
    expect(canSettle(hold())).toBe(true);
    for (const status of ["CAPTURED", "RELEASED", "EXPIRED", "CAPTURE_REVERSED"] as const) {
      expect(canSettle(hold({ status }))).toBe(false);
    }
  });
});
