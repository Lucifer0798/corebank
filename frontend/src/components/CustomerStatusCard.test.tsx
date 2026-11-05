import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useChangeCustomerStatus, useCustomerStatusChanges } from "../api/hooks";
import type { CustomerStatusChange, PagedResponse } from "../api/types";
import { CustomerStatusCard } from "./CustomerStatusCard";

/**
 * Who gets a form, and that suspending or closing cannot be sent without a reason. The history is
 * the backend's; this only shows it.
 */
vi.mock("../api/hooks", () => ({ useCustomerStatusChanges: vi.fn(), useChangeCustomerStatus: vi.fn() }));

const CUSTOMER_ID = "cccccccc-0000-4000-8000-000000000001";
const mutate = vi.fn();

function history(content: CustomerStatusChange[]) {
  vi.mocked(useCustomerStatusChanges).mockReturnValue({
    data: {
      content,
      page: 0,
      size: 10,
      totalElements: content.length,
      totalPages: 1,
      last: true,
    } satisfies PagedResponse<CustomerStatusChange>,
    error: null,
  } as unknown as ReturnType<typeof useCustomerStatusChanges>);
}

beforeEach(() => {
  mutate.mockClear();
  vi.mocked(useChangeCustomerStatus).mockReturnValue({
    mutate,
    isPending: false,
    error: null,
  } as unknown as ReturnType<typeof useChangeCustomerStatus>);
  history([]);
});

afterEach(cleanup);

describe("CustomerStatusCard", () => {
  it("will not suspend without a reason, and sends the one given", () => {
    render(<CustomerStatusCard customerId={CUSTOMER_ID} status="ACTIVE" admin />);
    const suspend = screen.getByRole("button", { name: "Suspend" }) as HTMLButtonElement;

    expect(suspend.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "  " } });
    expect(suspend.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "Fraud report" } });
    fireEvent.click(suspend);
    expect(mutate).toHaveBeenCalledWith({ action: "suspend", reason: "Fraud report" });
  });

  it("lets a reinstatement through without a reason", () => {
    render(<CustomerStatusCard customerId={CUSTOMER_ID} status="SUSPENDED" admin />);

    fireEvent.click(screen.getByRole("button", { name: "Reinstate" }));

    expect(mutate).toHaveBeenCalledWith({ action: "reinstate", reason: undefined });
  });

  it("asks for a reason again when the action changes to closing", () => {
    render(<CustomerStatusCard customerId={CUSTOMER_ID} status="SUSPENDED" admin />);

    fireEvent.change(screen.getByLabelText("Change"), { target: { value: "close" } });

    expect((screen.getByRole("button", { name: "Close customer" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("says what a suspension does, while it is in force", () => {
    render(<CustomerStatusCard customerId={CUSTOMER_ID} status="SUSPENDED" admin={false} />);
    expect(screen.getByText(/Payments in still arrive/)).toBeTruthy();
    cleanup();

    render(<CustomerStatusCard customerId={CUSTOMER_ID} status="ACTIVE" admin={false} />);
    expect(screen.queryByText(/Payments in still arrive/)).toBeNull();
  });

  it("shows a teller the history with who and why, and no form", () => {
    history([{
      id: "eeeeeeee-0000-4000-8000-000000000001",
      fromStatus: "ACTIVE",
      toStatus: "SUSPENDED",
      changedBySubject: "admin-subject",
      changedByName: "priya",
      reason: "Fraud report",
      changedAt: "2026-10-10T09:00:00Z",
    }]);
    render(<CustomerStatusCard customerId={CUSTOMER_ID} status="SUSPENDED" admin={false} />);

    expect(screen.getByText("priya")).toBeTruthy();
    expect(screen.getByText("Fraud report")).toBeTruthy();
    expect(screen.queryByLabelText("Change")).toBeNull();
  });

  it("offers nothing to change on a closed customer, but still shows the history", () => {
    render(<CustomerStatusCard customerId={CUSTOMER_ID} status="CLOSED" admin />);

    expect(screen.queryByLabelText("Change")).toBeNull();
    expect(screen.getByText("No changes recorded.")).toBeTruthy();
  });
});
