import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useLedgerCheck } from "../api/hooks";
import type { LedgerCheckReport } from "../api/types";
import { LedgerCheckCard } from "./LedgerCheckCard";

/** A clean result must read as one, and a failing one has to say where to look. */
vi.mock("../api/hooks", () => ({ useLedgerCheck: vi.fn() }));

const refetch = vi.fn();

function showing(report: LedgerCheckReport | undefined, isFetching = false) {
  vi.mocked(useLedgerCheck).mockReturnValue({
    data: report,
    error: null,
    isFetching,
    refetch,
  } as unknown as ReturnType<typeof useLedgerCheck>);
}

function report(overrides: Partial<LedgerCheckReport> = {}): LedgerCheckReport {
  return {
    checkedAt: "2026-11-06T02:30:00Z",
    clean: true,
    checks: [
      { check: "LEDGER_BALANCED", rule: "Debits equal credits", discrepancies: [], more: false },
      { check: "BALANCE_MATCHES_LEDGER", rule: "Balances match their entries", discrepancies: [], more: false },
    ],
    ...overrides,
  };
}

function failing(more = false): LedgerCheckReport {
  return report({
    clean: false,
    checks: [
      { check: "LEDGER_BALANCED", rule: "Debits equal credits", discrepancies: [], more: false },
      {
        check: "BALANCE_MATCHES_LEDGER",
        rule: "Balances match their entries",
        discrepancies: [{ subject: "100100000001", detail: "balance 105.0000, ledger entries total 100.0000" }],
        more,
      },
    ],
  });
}

beforeEach(() => {
  refetch.mockClear();
});

afterEach(cleanup);

describe("LedgerCheckCard", () => {
  it("does not claim anything before it has run, and runs when asked", () => {
    showing(undefined);
    render(<LedgerCheckCard />);

    expect(screen.queryByText("All clear.")).toBeNull();
    expect(screen.queryByText(/checks failed/)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Run the check" }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it("says all clear when everything passed", () => {
    showing(report());
    render(<LedgerCheckCard />);

    expect(screen.getByText("All clear.")).toBeTruthy();
    expect(screen.getByText(/2 checks passed/)).toBeTruthy();
  });

  it("says what failed and where, and leaves out what passed", () => {
    showing(failing());
    render(<LedgerCheckCard />);

    expect(screen.queryByText("All clear.")).toBeNull();
    expect(screen.getByText(/1 of 2 checks failed/)).toBeTruthy();
    expect(screen.getByText("Balances match their entries")).toBeTruthy();
    expect(screen.getByText("100100000001")).toBeTruthy();
    expect(screen.getByText("balance 105.0000, ledger entries total 100.0000")).toBeTruthy();
    expect(screen.queryByText("Debits equal credits")).toBeNull();
  });

  it("says when there are more than it lists", () => {
    showing(failing(true));
    render(<LedgerCheckCard />);
    expect(screen.getByText("There are more than the 1 listed.")).toBeTruthy();
    cleanup();

    showing(failing(false));
    render(<LedgerCheckCard />);
    expect(screen.queryByText(/There are more/)).toBeNull();
  });

  it("will not run twice at once", () => {
    showing(undefined, true);
    render(<LedgerCheckCard />);

    expect((screen.getByRole("button", { name: "Checking…" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
