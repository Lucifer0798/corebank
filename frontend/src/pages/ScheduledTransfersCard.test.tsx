import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  useCancelScheduledTransfer,
  useCreateScheduledTransfer,
  useScheduledTransfers,
} from "../api/hooks";
import type { Account, PagedResponse, ScheduledTransfer } from "../api/types";
import { ScheduledTransfersCard } from "./AccountDetailPage";

/**
 * The wiring the pure helpers in `schedule.ts` cannot reach: who gets a Cancel button, whether the
 * attention row actually renders, and the sign of the amount -- which is the only thing telling a
 * payer's copy of a mandate apart from the payee's, since both accounts list the same row.
 */
vi.mock("../api/hooks", () => ({
  useScheduledTransfers: vi.fn(),
  useCancelScheduledTransfer: vi.fn(),
  useCreateScheduledTransfer: vi.fn(),
}));

const ACCOUNT_ID = "11111111-1111-1111-1111-111111111111";
const OTHER_ID = "22222222-2222-2222-2222-222222222222";

const cancelMutate = vi.fn();

function account(): Account {
  return {
    id: ACCOUNT_ID,
    accountNumber: "100100000001",
    customerId: "cccccccc-0000-4000-8000-000000000001",
    accountType: "SAVINGS",
    status: "ACTIVE",
    currency: "INR",
    balance: 1000,
    availableBalance: 1000,
    overdraftLimit: 0,
    openedAt: "2026-01-01T00:00:00Z",
  } as Account;
}

function schedule(overrides: Partial<ScheduledTransfer> = {}): ScheduledTransfer {
  return {
    id: "aaaaaaaa-0000-4000-8000-000000000001",
    sourceAccountId: ACCOUNT_ID,
    destinationAccountId: OTHER_ID,
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

function listing(content: ScheduledTransfer[]) {
  vi.mocked(useScheduledTransfers).mockReturnValue({
    data: {
      content,
      page: 0,
      size: 20,
      totalElements: content.length,
      totalPages: 1,
      last: true,
    } satisfies PagedResponse<ScheduledTransfer>,
    error: null,
  } as unknown as ReturnType<typeof useScheduledTransfers>);
}

beforeEach(() => {
  cancelMutate.mockClear();
  vi.mocked(useCancelScheduledTransfer).mockReturnValue({
    mutate: cancelMutate,
    isPending: false,
    error: null,
  } as unknown as ReturnType<typeof useCancelScheduledTransfer>);
  // The create form renders inside this card for staff, so its hook has to be stubbed even in
  // tests that never touch the form -- an unstubbed mock returns undefined and the card throws.
  vi.mocked(useCreateScheduledTransfer).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isSuccess: false,
    error: null,
  } as unknown as ReturnType<typeof useCreateScheduledTransfer>);
});

afterEach(cleanup);

describe("ScheduledTransfersCard", () => {
  it("shows an outgoing mandate as negative on the paying account", () => {
    listing([schedule()]);

    render(<ScheduledTransfersCard account={account()} staff />);

    expect(screen.getByText("MONTHLY")).toBeTruthy();
    expect(screen.getByText(/-.*750/)).toBeTruthy();
  });

  it("shows the same mandate as positive on the receiving account", () => {
    // One row, two accounts. If the sign did not flip, a customer would read every standing
    // instruction paid *to* them as money going out.
    listing([schedule({ sourceAccountId: OTHER_ID, destinationAccountId: ACCOUNT_ID })]);

    render(<ScheduledTransfersCard account={account()} staff />);

    const amount = screen.getByText(/750/);
    expect(amount.textContent).not.toContain("-");
  });

  it("surfaces a failing mandate instead of leaving it looking healthy", () => {
    listing([schedule({
      consecutiveFailures: 2,
      lastError: "Account 100100000001 has 50.0000 available but 750.0000 was requested",
    })]);

    render(<ScheduledTransfersCard account={account()} staff />);

    expect(screen.getByText(/2 attempts failed/)).toBeTruthy();
    expect(screen.getByText(/750.0000 was requested/)).toBeTruthy();
  });

  it("offers Cancel on a live mandate and not on a stopped one", () => {
    listing([
      schedule({ id: "live", status: "ACTIVE" }),
      schedule({ id: "done", status: "COMPLETED", nextRunOn: null }),
    ]);

    render(<ScheduledTransfersCard account={account()} staff />);

    // One button, not two: the backend refuses cancelling a stopped mandate, so offering it
    // would produce an error the user could not have avoided.
    expect(screen.getAllByRole("button", { name: "Cancel" })).toHaveLength(1);
  });

  it("gives a customer no Cancel button and no create form", () => {
    listing([schedule()]);

    render(<ScheduledTransfersCard account={account()} staff={false} />);

    // They can see their own standing instructions -- the backend allows the read -- but both
    // write paths are staff-only, so neither control should exist for them at all.
    expect(screen.queryByRole("button", { name: "Cancel" })).toBeNull();
    expect(screen.queryByLabelText("Destination account id")).toBeNull();
    expect(screen.getByText("MONTHLY")).toBeTruthy();
  });

  it("says so plainly when there is nothing scheduled", () => {
    listing([]);

    render(<ScheduledTransfersCard account={account()} staff />);

    expect(screen.getByText(/Nothing scheduled against this account/)).toBeTruthy();
  });

  it("renders a due date as the calendar day the backend sent", () => {
    listing([schedule({ nextRunOn: "2026-11-01" })]);

    render(<ScheduledTransfersCard account={account()} staff />);

    const row = screen.getByText("MONTHLY").closest("tr");
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText(/Nov/)).toBeTruthy();
  });

  it("renders a mandate with nothing further due without breaking", () => {
    // nextRunOn is null on everything terminal, and an em dash is better than "Invalid Date".
    listing([schedule({ status: "CANCELLED", nextRunOn: null })]);

    render(<ScheduledTransfersCard account={account()} staff />);

    expect(screen.getByText("CANCELLED")).toBeTruthy();
    expect(screen.queryByText(/Invalid Date/)).toBeNull();
  });
});
