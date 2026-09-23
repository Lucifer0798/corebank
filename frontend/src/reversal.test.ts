import { describe, expect, it } from "vitest";
import type { Transaction } from "./api/types";
import { reversalAvailability } from "./reversal";

/**
 * The rule this pins is the one the UI would otherwise get wrong silently: offering a Reverse
 * form on something the backend will refuse. Both refusals are permanent-looking to a user but
 * mean different things, and only one of them is worth retrying against a different record.
 */
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

describe("reversalAvailability", () => {
  it("offers the form for an ordinary posted transaction", () => {
    expect(reversalAvailability(transaction())).toEqual({ available: true });
  });

  it("offers the form for every posted type, not just deposits", () => {
    // A withdrawal and a transfer are as reversible as a deposit; only REVERSAL is special.
    expect(reversalAvailability(transaction({ type: "WITHDRAWAL" })).available).toBe(true);
    expect(reversalAvailability(transaction({ type: "TRANSFER" })).available).toBe(true);
  });

  it("refuses a transaction that has already been reversed", () => {
    const result = reversalAvailability(transaction({ status: "REVERSED" }));

    expect(result.available).toBe(false);
    expect(result.available === false && result.reason).toContain("already been reversed");
  });

  it("refuses a reversal, and says what to do instead", () => {
    // The useful half of this message: "post the original movement again". Without it the UI
    // just says no to an admin who has a genuine correction to make.
    const result = reversalAvailability(
      transaction({ type: "REVERSAL", reversalOf: "TXN-20250417-0000AAAA" }),
    );

    expect(result.available).toBe(false);
    expect(result.available === false && result.reason).toContain("post the original movement again");
  });

  it("calls a reversal irreversible before it calls it already-reversed", () => {
    // Matches BankTransaction.assertReversible's order. Being a reversal is permanent; having
    // been reversed is a state someone else moved you past. If both were ever true at once,
    // telling a user to retry later would be the wrong advice.
    const result = reversalAvailability(transaction({ type: "REVERSAL", status: "REVERSED" }));

    expect(result.available === false && result.reason).toContain("cannot itself be reversed");
  });
});
