import { describe, expect, it } from "vitest";
import { fxSummary, isAccountId, quoteSummary } from "./fx";

describe("fxSummary", () => {
  it("says what a cross-currency posting converted, and at what rate", () => {
    const summary = fxSummary({
      amount: 10000,
      currency: "INR",
      fx: { exchangeRate: 0.01194, counterAmount: 119.4, counterCurrency: "USD" },
    });
    expect(summary).toContain("10,000.00");
    expect(summary).toContain("119.40");
    expect(summary).toContain("0.01194");
  });

  it("says nothing about a posting that did not cross currencies", () => {
    expect(fxSummary({ amount: 500, currency: "INR", fx: null })).toBeNull();
  });
});

describe("quoteSummary", () => {
  it("shows the margin rather than folding it into the rate, and says it is an estimate", () => {
    const text = quoteSummary({
      fromCurrency: "INR",
      toCurrency: "USD",
      fromAmount: 10000,
      toAmount: 119.4,
      rateApplied: 0.01194,
      midRate: 0.012,
      spreadBps: 50,
      asOf: "2026-10-24T10:00:00Z",
    });
    expect(text).toContain("119.40");
    expect(text).toContain("mid 0.012");
    expect(text).toContain("50 bps");
    expect(text).toContain("fixed when the transfer posts");
  });
});

describe("isAccountId", () => {
  it("accepts a complete account id and nothing still being typed", () => {
    expect(isAccountId("11111111-1111-1111-1111-111111111111")).toBe(true);
    expect(isAccountId(" 11111111-1111-1111-1111-111111111111 ")).toBe(true);
    expect(isAccountId("11111111-1111")).toBe(false);
    expect(isAccountId("")).toBe(false);
  });
});
