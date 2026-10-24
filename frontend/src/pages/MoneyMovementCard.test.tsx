import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAccount, useDeposit, useFxQuote, useTransfer, useWithdraw } from "../api/hooks";
import type { Account } from "../api/types";
import { MoneyMovementCard } from "./AccountDetailPage";

/**
 * The form sent no currency, so the backend assumed rupees and refused every deposit, withdrawal
 * and transfer on any other account. And a cross-currency transfer went out without the teller
 * seeing what it would convert to.
 */
vi.mock("../api/hooks", () => ({
  useDeposit: vi.fn(),
  useWithdraw: vi.fn(),
  useTransfer: vi.fn(),
  useAccount: vi.fn(),
  useFxQuote: vi.fn(),
}));

const DESTINATION = "22222222-2222-2222-2222-222222222222";
const deposit = vi.fn();
const withdraw = vi.fn();
const transfer = vi.fn();

function dollarAccount(): Account {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    accountNumber: "100100000009",
    customerId: "c1",
    accountType: "SAVINGS",
    status: "ACTIVE",
    currency: "USD",
    balance: 500,
    availableBalance: 500,
    overdraftLimit: 0,
    openedAt: "2026-01-01T00:00:00Z",
  } as Account;
}

function mutation(fn: ReturnType<typeof vi.fn>) {
  return { mutate: fn, isPending: false, isSuccess: false, error: null } as never;
}

function destinationIn(currency: string | null) {
  vi.mocked(useAccount).mockReturnValue(
    { data: currency ? { id: DESTINATION, currency } : undefined } as unknown as ReturnType<typeof useAccount>);
}

beforeEach(() => {
  deposit.mockClear();
  withdraw.mockClear();
  transfer.mockClear();
  vi.mocked(useDeposit).mockReturnValue(mutation(deposit));
  vi.mocked(useWithdraw).mockReturnValue(mutation(withdraw));
  vi.mocked(useTransfer).mockReturnValue(mutation(transfer));
  destinationIn(null);
  vi.mocked(useFxQuote).mockReturnValue({
    data: {
      fromCurrency: "USD", toCurrency: "INR", fromAmount: 100, toAmount: 8300,
      rateApplied: 83, midRate: 83.4, spreadBps: 50, asOf: "2026-10-24T10:00:00Z",
    },
    error: null,
  } as unknown as ReturnType<typeof useFxQuote>);
});

afterEach(cleanup);

function submitWith(buttonName: string) {
  fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: "100" } });
  const button = screen.getAllByRole("button", { name: buttonName }).find((b) => b.getAttribute("type") === "submit");
  fireEvent.submit(button!.closest("form") as HTMLFormElement);
}

function openTransfer() {
  fireEvent.click(screen.getByRole("button", { name: "Transfer" }));
  fireEvent.change(screen.getByLabelText("Destination account id"), { target: { value: DESTINATION } });
}

describe("MoneyMovementCard", () => {
  it("deposits and withdraws in the account's own currency", () => {
    render(<MoneyMovementCard account={dollarAccount()} />);
    submitWith("Deposit");
    expect(deposit).toHaveBeenCalledWith(expect.objectContaining({ amount: 100, currency: "USD" }));

    fireEvent.click(screen.getByRole("button", { name: "Withdraw" }));
    submitWith("Withdraw");
    expect(withdraw).toHaveBeenCalledWith(expect.objectContaining({ amount: 100, currency: "USD" }));
  });

  it("sends a transfer in the sending account's currency", () => {
    render(<MoneyMovementCard account={dollarAccount()} />);
    openTransfer();
    submitWith("Transfer");

    expect(transfer).toHaveBeenCalledWith(expect.objectContaining({
      destinationAccountId: DESTINATION, amount: 100, currency: "USD",
    }));
  });

  it("quotes the conversion before a cross-currency transfer is sent", () => {
    destinationIn("INR");
    render(<MoneyMovementCard account={dollarAccount()} />);
    openTransfer();
    fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: "100" } });

    expect(screen.getByText(/The recipient receives about/).textContent).toContain("8,300.00");
  });

  it("shows no quote when both accounts are in the same currency", () => {
    destinationIn("USD");
    render(<MoneyMovementCard account={dollarAccount()} />);
    openTransfer();
    fireEvent.change(screen.getByLabelText(/^Amount/), { target: { value: "100" } });

    expect(screen.queryByText(/The recipient receives about/)).toBeNull();
  });
});
