import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useRecentEntries, useSpendingSummary } from "../api/insightsHooks";
import type { SpendingSummary } from "../api/insightsTypes";
import { SpendingInsights } from "./SpendingInsights";

/**
 * A customer with a rupee and a dollar account. The card used to show one "Total spent" -- the
 * service added the two currencies together and labelled the sum INR.
 */
vi.mock("../api/insightsHooks", () => ({ useSpendingSummary: vi.fn(), useRecentEntries: vi.fn() }));

afterEach(cleanup);

function summaryOf(summary: SpendingSummary) {
  vi.mocked(useSpendingSummary).mockReturnValue(
    { data: summary, isLoading: false, error: null } as unknown as ReturnType<typeof useSpendingSummary>);
  vi.mocked(useRecentEntries).mockReturnValue({ data: [] } as unknown as ReturnType<typeof useRecentEntries>);
}

describe("SpendingInsights", () => {
  it("shows a total per currency, and each category in its own currency", () => {
    summaryOf({
      customer_id: "c1",
      totals: [{ currency: "INR", spent: "500.00" }, { currency: "USD", spent: "35.00" }],
      categories: [
        { category: "DINING", currency: "INR", spent: "500.00", entries: 3 },
        { category: "DINING", currency: "USD", spent: "10.00", entries: 1 },
        { category: "SHOPPING", currency: "USD", spent: "25.00", entries: 1 },
      ],
      accounts: ["100100000001", "100100000002"],
    });

    const { container } = render(<SpendingInsights customerId="c1" />);

    expect(screen.getByText("Total spent (INR)")).toBeTruthy();
    // Three category rows, not six: each currency's breakdown lists only that currency's categories.
    expect(container.querySelectorAll(".category-row")).toHaveLength(3);
    expect(screen.getByText("Total spent (USD)")).toBeTruthy();
    // The two currencies are never combined into one figure anywhere on the card.
    expect(screen.queryByText(/535/)).toBeNull();
    const usdDining = screen.getAllByText(/10\.00/).map((node) => node.textContent ?? "");
    expect(usdDining.some((text) => text.includes("$"))).toBe(true);
  });

  it("shows a single total, unlabelled breakdown, for a customer in one currency", () => {
    summaryOf({
      customer_id: "c1",
      totals: [{ currency: "INR", spent: "850.00" }],
      categories: [{ category: "DINING", currency: "INR", spent: "850.00", entries: 2 }],
      accounts: ["100100000001"],
    });

    render(<SpendingInsights customerId="c1" />);

    expect(screen.getAllByText(/^Total spent/)).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: "INR" })).toBeNull();
  });
});
