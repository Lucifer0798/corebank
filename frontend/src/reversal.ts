import type { Transaction } from "./api/types";

/**
 * Whether a posting can be reversed, and when it cannot, what to say instead of offering a form.
 *
 * The backend is the authority here and stays so: it refuses with `REVERSAL_NOT_REVERSIBLE` (422)
 * or `ALREADY_REVERSED` (409) regardless of what this returns, and a stale page that offers the
 * form anyway simply gets that error back. This exists so an admin is told which rule applies
 * *before* submitting a reason rather than after, and because the distinction is worth teaching:
 * one of these is permanent and the other is a race they lost.
 *
 * The checks run in the same order as `BankTransaction.assertReversible`. Nothing currently
 * produces a transaction that is both a REVERSAL and REVERSED, so the order is not observable
 * today -- it matches deliberately, so that if that ever changes the two sides still agree on
 * which reason wins.
 */
export type ReversalAvailability =
  | { available: true }
  | { available: false; reason: string };

export function reversalAvailability(transaction: Transaction): ReversalAvailability {
  if (transaction.type === "REVERSAL") {
    return {
      available: false,
      reason:
        "A reversal cannot itself be reversed. To undo one, post the original movement again.",
    };
  }
  if (transaction.status === "REVERSED") {
    return {
      available: false,
      reason: "This posting has already been reversed.",
    };
  }
  return { available: true };
}
