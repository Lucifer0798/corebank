import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useTransaction } from "../api/hooks";
import type { Transaction } from "../api/types";
import { TransactionDetailPage } from "./TransactionDetailPage";

/**
 * A cross-currency transfer's page. Every leg used to be formatted in the transaction's currency,
 * so the dollar account's 119.40 read as rupees.
 */
vi.mock("react-oidc-context", () => ({ useAuth: () => ({ user: { access_token: "" } }) }));
vi.mock("../api/hooks", () => ({ useTransaction: vi.fn(), useReverseTransaction: vi.fn() }));

afterEach(cleanup);

const RUPEE = String.fromCodePoint(0x20b9);

function leg(accountNumber: string, direction: "DEBIT" | "CREDIT", amount: number, currency: string) {
  return { accountId: accountNumber, accountNumber, direction, amount, currency, balanceAfter: amount };
}

function fxTransfer(): Transaction {
  return {
    id: "t1",
    reference: "TXN-FX-1",
    type: "TRANSFER",
    status: "POSTED",
    amount: 10000,
    currency: "INR",
    description: "To USD",
    postedAt: "2026-10-24T10:00:00Z",
    reversalOf: null,
    legs: [
      leg("100100000001", "DEBIT", 10000, "INR"),
      leg("GL0000000010", "CREDIT", 10000, "INR"),
      leg("GL0000000011", "DEBIT", 119.4, "USD"),
      leg("100100000002", "CREDIT", 119.4, "USD"),
    ],
    fx: { exchangeRate: 0.01194, counterAmount: 119.4, counterCurrency: "USD" },
    initiatedBy: null,
  };
}

describe("TransactionDetailPage", () => {
  it("shows each leg in its own currency, and the conversion", () => {
    vi.mocked(useTransaction).mockReturnValue(
      { data: fxTransfer(), isLoading: false, error: null } as unknown as ReturnType<typeof useTransaction>);

    render(
      <MemoryRouter initialEntries={["/transactions/TXN-FX-1"]}>
        <Routes><Route path="/transactions/:reference" element={<TransactionDetailPage />} /></Routes>
      </MemoryRouter>,
    );

    // Two dollar legs, each with an amount and a balance after: four cells, none of them in rupees.
    const dollarCells = screen.getAllByText(/119\.40/).filter((cell) => cell.tagName === "TD");
    expect(dollarCells).toHaveLength(4);
    for (const cell of dollarCells) {
      expect(cell.textContent).not.toContain(RUPEE);
    }
    expect(screen.getByText(/Converted:/).textContent).toContain("0.01194");
  });
});
