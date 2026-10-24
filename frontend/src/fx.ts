import type { FxQuote, Transaction } from "./api/types";
import { formatAmount } from "./format";

/**
 * A cross-currency posting's conversion in words, or null for one that did not cross currencies.
 * "10,000.00 INR -> 119.40 USD at 0.01194".
 */
export function fxSummary(transaction: Pick<Transaction, "amount" | "currency" | "fx">): string | null {
  const fx = transaction.fx;
  if (!fx) {
    return null;
  }
  return `${formatAmount(transaction.amount, transaction.currency)} \u2192 `
    + `${formatAmount(fx.counterAmount, fx.counterCurrency)} at ${fx.exchangeRate}`;
}

/** What the recipient would get, with the margin shown rather than folded into the rate. */
export function quoteSummary(quote: FxQuote): string {
  return `The recipient receives about ${formatAmount(quote.toAmount, quote.toCurrency)} `
    + `at ${quote.rateApplied} (mid ${quote.midRate}, the bank's spread ${quote.spreadBps} bps). `
    + "The rate is fixed when the transfer posts.";
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Only a complete account id is worth looking up; anything shorter is still being typed. */
export function isAccountId(value: string): boolean {
  return UUID_PATTERN.test(value.trim());
}
