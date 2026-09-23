import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useReverseTransaction } from "../api/hooks";
import type { Transaction } from "../api/types";
import { ReversalCard } from "./TransactionDetailPage";

/**
 * The card is the only place a reversal can actually be performed, and every one of its states is
 * a decision rather than a rendering detail: which of the two refusals to show, and -- the one
 * worth guarding hardest -- that the confirmation survives the original refetching as REVERSED.
 *
 * The hook is mocked because none of that is about HTTP. What the real hook does with the caches
 * afterwards is its own concern; what this file pins is what an admin sees.
 */
vi.mock("../api/hooks", () => ({
  useReverseTransaction: vi.fn(),
  useTransaction: vi.fn(),
}));

type Mutation = ReturnType<typeof useReverseTransaction>;

const mutate = vi.fn();

function mockMutation(overrides: Partial<Mutation> = {}) {
  vi.mocked(useReverseTransaction).mockReturnValue({
    mutate,
    isPending: false,
    isSuccess: false,
    error: null,
    data: undefined,
    ...overrides,
  } as unknown as Mutation);
}

function transaction(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: "6f1c9b6e-0000-4000-8000-000000000001",
    reference: "TXN-20250417-9F3A2B1C",
    type: "DEPOSIT",
    status: "POSTED",
    amount: 250,
    currency: "INR",
    description: "Counter deposit",
    postedAt: "2026-04-17T09:30:00Z",
    reversalOf: null,
    legs: [],
    ...overrides,
  };
}

function renderCard(tx: Transaction) {
  return render(
    <MemoryRouter>
      <ReversalCard transaction={tx} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mutate.mockClear();
  mockMutation();
});

afterEach(cleanup);

describe("ReversalCard", () => {
  it("offers the form, and warns what a reversal does, for a posted transaction", () => {
    renderCard(transaction());

    expect(screen.getByLabelText("Reason")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Reverse this transaction" })).toBeTruthy();
    expect(screen.getByText(/cannot be undone/)).toBeTruthy();
  });

  it("submits the reason, trimmed, rather than whatever was typed", () => {
    renderCard(transaction());

    fireEvent.change(screen.getByLabelText("Reason"), {
      target: { value: "   Keyed twice at branch 004   " },
    });
    fireEvent.submit(screen.getByRole("button", { name: "Reverse this transaction" }));

    // A reason of pure whitespace would pass the browser's `required` and then be refused by the
    // backend's @NotBlank, so the trim has to happen before the request, not after.
    expect(mutate).toHaveBeenCalledWith({ reason: "Keyed twice at branch 004" });
  });

  it("replaces the form with a reason when the posting is already reversed", () => {
    renderCard(transaction({ status: "REVERSED" }));

    expect(screen.queryByLabelText("Reason")).toBeNull();
    expect(screen.getByText(/already been reversed/)).toBeTruthy();
  });

  it("replaces the form with a reason when the posting is itself a reversal", () => {
    renderCard(transaction({ type: "REVERSAL", reversalOf: "TXN-20250417-0000AAAA" }));

    expect(screen.queryByLabelText("Reason")).toBeNull();
    expect(screen.getByText(/post the original movement again/)).toBeTruthy();
  });

  it("keeps the confirmation visible after the original refetches as REVERSED", () => {
    // The ordering trap. On success the original is invalidated and comes back REVERSED, so an
    // availability-first branch would swap the confirmation -- and the only link to the
    // correction just posted -- for "already been reversed", leaving the admin to go and find it.
    mockMutation({
      isSuccess: true,
      data: transaction({ reference: "TXN-20250417-REVERSAL", type: "REVERSAL" }),
    });

    renderCard(transaction({ status: "REVERSED" }));

    expect(screen.getByText(/Reversed by/)).toBeTruthy();
    const link = screen.getByRole("link", { name: "TXN-20250417-REVERSAL" });
    expect(link.getAttribute("href")).toBe("/transactions/TXN-20250417-REVERSAL");
    expect(screen.queryByText(/already been reversed/)).toBeNull();
  });

  it("disables the button while the reversal is in flight", () => {
    mockMutation({ isPending: true });

    renderCard(transaction());

    const button = screen.getByRole("button", { name: "Reversing…" });
    expect((button as HTMLButtonElement).disabled).toBe(true);
  });
});
