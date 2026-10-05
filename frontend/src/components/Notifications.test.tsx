import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useNotifications } from "../api/hooks";
import type { Notification, PagedResponse } from "../api/types";
import { NotificationsCard } from "./Notifications";

/**
 * Which list is asked for, and who gets a link: the two decisions this card makes. A customer
 * shown a link to a transaction gets a 403 for following it, and a card that fetched a customer's
 * list by id from a customer login would 403 outright.
 */
vi.mock("../api/hooks", () => ({ useNotifications: vi.fn() }));

const CUSTOMER_ID = "cccccccc-0000-4000-8000-000000000001";

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    id: "aaaaaaaa-0000-4000-8000-000000000001",
    kind: "TRANSACTION",
    accountId: "11111111-1111-1111-1111-111111111111",
    transactionReference: "TXN-20261004-0001",
    transactionStatus: "POSTED",
    direction: "CREDIT",
    scheduledTransferId: null,
    dueOn: null,
    amount: 500,
    currency: "INR",
    message: "500.00 INR credited to account XXXX0001",
    createdAt: "2026-10-04T10:00:00Z",
    ...overrides,
  };
}

function listing(content: Notification[]) {
  vi.mocked(useNotifications).mockReturnValue({
    data: {
      content,
      page: 0,
      size: 10,
      totalElements: content.length,
      totalPages: 1,
      last: true,
    } satisfies PagedResponse<Notification>,
    error: null,
  } as unknown as ReturnType<typeof useNotifications>);
}

function renderCard(customerId?: string) {
  return render(
    <MemoryRouter>
      <NotificationsCard customerId={customerId} />
    </MemoryRouter>,
  );
}

afterEach(() => {
  cleanup();
  vi.mocked(useNotifications).mockReset();
});

describe("NotificationsCard", () => {
  it("shows the customer the message as the backend wrote it, with no link they cannot follow", () => {
    listing([notification()]);
    renderCard();

    // The customer's own list: no id, so the hook asks for /customers/me/notifications.
    expect(vi.mocked(useNotifications)).toHaveBeenCalledWith(undefined, 0);
    expect(screen.getByText("500.00 INR credited to account XXXX0001")).toBeTruthy();
    expect(screen.getByText("TXN-20261004-0001")).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
  });

  it("links the reference for staff, who can open the transaction", () => {
    listing([notification()]);
    renderCard(CUSTOMER_ID);

    expect(vi.mocked(useNotifications)).toHaveBeenCalledWith(CUSTOMER_ID, 0);
    expect(screen.getByRole("link", { name: "TXN-20261004-0001" }).getAttribute("href"))
      .toBe("/transactions/TXN-20261004-0001");
  });

  it("marks a reversal so it reads as a correction, not as another payment", () => {
    listing([
      notification({
        id: "aaaaaaaa-0000-4000-8000-000000000002",
        transactionStatus: "REVERSED",
        message: "A credit of 500.00 INR to account XXXX0001 was reversed",
      }),
      notification(),
    ]);
    renderCard();

    expect(screen.getAllByText("REVERSED")).toHaveLength(1);
  });

  it("links a missed standing-order payment to the account page, for a customer too", () => {
    // There is no transaction to open -- nothing moved -- and the account page is where the
    // instruction itself is listed. Unlike /transactions, a customer can open their own account.
    listing([
      notification({
        kind: "SCHEDULED_TRANSFER_FAILED",
        transactionReference: null,
        transactionStatus: null,
        direction: null,
        scheduledTransferId: "bbbbbbbb-0000-4000-8000-000000000001",
        dueOn: "2026-11-01",
        message: "Your scheduled transfer of 750.00 INR from account XXXX0001, due 1 Nov 2026, was not made",
      }),
    ]);
    renderCard();

    expect(screen.getByRole("link", { name: "Standing instruction" }).getAttribute("href"))
      .toBe("/accounts/11111111-1111-1111-1111-111111111111");
    expect(screen.getByText("FAILED")).toBeTruthy();
  });

  it("marks a stopped standing order as suspended", () => {
    listing([
      notification({
        kind: "SCHEDULED_TRANSFER_SUSPENDED",
        transactionReference: null,
        transactionStatus: null,
        direction: null,
        scheduledTransferId: "bbbbbbbb-0000-4000-8000-000000000001",
        dueOn: "2026-11-01",
        message: "... it has been stopped",
      }),
    ]);
    renderCard();

    expect(screen.getByText("SUSPENDED")).toBeTruthy();
    expect(screen.queryByText("FAILED")).toBeNull();
  });

  it("says so when there is nothing to show", () => {
    listing([]);
    renderCard();

    expect(screen.getByText("Nothing yet.")).toBeTruthy();
  });
});
