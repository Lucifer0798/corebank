import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAccountStatusChanges, useChangeAccountStatus } from "../api/hooks";
import type { Account, AccountStatusChange, PagedResponse } from "../api/types";
import { AccountStatusCard, statusActionsFor, statusReasonRequired } from "./AccountStatusCard";

/**
 * Who gets which action, and that freezing or closing cannot be sent without a reason. The history
 * is the backend's; this only shows it.
 */
vi.mock("../api/hooks", () => ({ useAccountStatusChanges: vi.fn(), useChangeAccountStatus: vi.fn() }));

const ACCOUNT_ID = "11111111-1111-1111-1111-111111111111";
const mutate = vi.fn();

function account(overrides: Partial<Account> = {}): Account {
  return {
    id: ACCOUNT_ID,
    accountNumber: "100100000001",
    customerId: "cccccccc-0000-4000-8000-000000000001",
    accountType: "SAVINGS",
    status: "ACTIVE",
    currency: "INR",
    balance: 0,
    availableBalance: 0,
    overdraftLimit: 0,
    openedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  } as Account;
}

function history(content: AccountStatusChange[]) {
  vi.mocked(useAccountStatusChanges).mockReturnValue({
    data: {
      content,
      page: 0,
      size: 10,
      totalElements: content.length,
      totalPages: 1,
      last: true,
    } satisfies PagedResponse<AccountStatusChange>,
    error: null,
  } as unknown as ReturnType<typeof useAccountStatusChanges>);
}

beforeEach(() => {
  mutate.mockClear();
  vi.mocked(useChangeAccountStatus).mockReturnValue({
    mutate,
    isPending: false,
    error: null,
  } as unknown as ReturnType<typeof useChangeAccountStatus>);
  history([]);
});

afterEach(cleanup);

describe("AccountStatusCard", () => {
  it("will not freeze without a reason, and sends the one given", () => {
    render(<AccountStatusCard account={account()} admin={false} />);
    const freeze = screen.getByRole("button", { name: "Freeze" }) as HTMLButtonElement;

    expect(freeze.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "  " } });
    expect(freeze.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "Card reported stolen" } });
    fireEvent.click(freeze);
    expect(mutate).toHaveBeenCalledWith({ accountId: ACCOUNT_ID, action: "freeze", reason: "Card reported stolen" });
  });

  it("lets an unfreeze through without a reason", () => {
    render(<AccountStatusCard account={account({ status: "FROZEN" })} admin={false} />);

    fireEvent.click(screen.getByRole("button", { name: "Unfreeze" }));

    expect(mutate).toHaveBeenCalledWith({ accountId: ACCOUNT_ID, action: "unfreeze", reason: undefined });
  });

  it("shows the history with who and why", () => {
    history([{
      id: "eeeeeeee-0000-4000-8000-000000000001",
      fromStatus: "ACTIVE",
      toStatus: "FROZEN",
      changedBySubject: "teller-subject",
      changedByName: "meena",
      reason: "Card reported stolen",
      changedAt: "2026-10-10T09:00:00Z",
    }]);
    render(<AccountStatusCard account={account({ status: "FROZEN" })} admin={false} />);

    expect(screen.getByText("meena")).toBeTruthy();
    expect(screen.getByText("Card reported stolen")).toBeTruthy();
  });

  it("offers nothing to change on a closed account, but still shows its history", () => {
    render(<AccountStatusCard account={account({ status: "CLOSED" })} admin />);

    expect(screen.queryByLabelText("Change")).toBeNull();
    expect(screen.getByText(/earlier changes were never kept/)).toBeTruthy();
  });
});

describe("statusActionsFor", () => {
  it("offers closing only to an admin -- a teller would only get a 403", () => {
    expect(statusActionsFor("ACTIVE", false)).toEqual(["freeze"]);
    expect(statusActionsFor("ACTIVE", true)).toEqual(["freeze", "close"]);
    expect(statusActionsFor("FROZEN", false)).toEqual(["unfreeze"]);
    expect(statusActionsFor("CLOSED", true)).toEqual([]);
  });
});

describe("statusReasonRequired", () => {
  it("asks for a reason to freeze or close, not to unfreeze", () => {
    expect(statusReasonRequired("freeze")).toBe(true);
    expect(statusReasonRequired("close")).toBe(true);
    expect(statusReasonRequired("unfreeze")).toBe(false);
  });
});
