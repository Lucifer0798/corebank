import { describe, expect, it } from "vitest";
import { canReviewKyc, customerReasonRequired, customerStatusActionsFor } from "./customerStatus";

describe("customerStatusActionsFor", () => {
  it("offers an admin what fits the status", () => {
    expect(customerStatusActionsFor("ACTIVE", true)).toEqual(["suspend", "close"]);
    expect(customerStatusActionsFor("SUSPENDED", true)).toEqual(["reinstate", "close"]);
  });

  it("offers nothing on a closed customer -- closing is final", () => {
    expect(customerStatusActionsFor("CLOSED", true)).toEqual([]);
  });

  it("offers a teller nothing -- every change would only get a 403", () => {
    expect(customerStatusActionsFor("ACTIVE", false)).toEqual([]);
    expect(customerStatusActionsFor("SUSPENDED", false)).toEqual([]);
  });
});

describe("customerReasonRequired", () => {
  it("asks for a reason to suspend or close, not to reinstate", () => {
    expect(customerReasonRequired("suspend")).toBe(true);
    expect(customerReasonRequired("close")).toBe(true);
    expect(customerReasonRequired("reinstate")).toBe(false);
  });
});

describe("canReviewKyc", () => {
  it("is refused only on a closed customer", () => {
    expect(canReviewKyc("ACTIVE")).toBe(true);
    expect(canReviewKyc("SUSPENDED")).toBe(true);
    expect(canReviewKyc("CLOSED")).toBe(false);
  });
});
