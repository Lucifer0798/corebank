import { describe, expect, it } from "vitest";
import { canPayOutInterest, payableInterest } from "./interest";

/** What a pay-out would pay, and when it is worth offering at all. */
describe("payableInterest", () => {
  it("pays whole paise only, rounded down, as the backend does", () => {
    expect(payableInterest(19.178)).toBe(19.17);
    expect(payableInterest(0.0099)).toBe(0);
    expect(payableInterest(0.01)).toBe(0.01);
  });

  it("is not thrown off by floating point -- 0.29 is 0.29, not 0.28", () => {
    // 0.29 * 100 is 28.999999999999996 in a double, so rounding down straight from there loses a
    // paisa the backend would pay -- and the button would then name the wrong amount.
    expect(payableInterest(0.29)).toBe(0.29);
    expect(payableInterest(1.13)).toBe(1.13);
  });
});

describe("canPayOutInterest", () => {
  it("needs a whole paisa and an account that is not closed", () => {
    expect(canPayOutInterest({ status: "ACTIVE", accruedInterest: 0.01 })).toBe(true);
    expect(canPayOutInterest({ status: "ACTIVE", accruedInterest: 0.0099 })).toBe(false);
    expect(canPayOutInterest({ status: "CLOSED", accruedInterest: 3 })).toBe(false);
  });
});
