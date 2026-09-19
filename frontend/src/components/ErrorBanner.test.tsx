import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import type { ProblemDetail } from "../api/types";
import { ErrorBanner, fieldErrors } from "./ErrorBanner";

/**
 * The component the user actually reads when something fails. Its whole job is picking the right
 * message out of four differently-shaped inputs, so each branch is worth pinning: an ApiError
 * must surface the backend's own problem detail rather than the generic Error message its
 * constructor also sets, and a failure with no usable message must still say something.
 *
 * <p>Doubles as the proof that the test setup renders real TSX in a real DOM -- if the React
 * plugin or the jsdom environment were misconfigured, this file is what fails first.
 */

function problem(overrides: Partial<ProblemDetail> = {}): ProblemDetail {
  return {
    type: "https://corebank.example/problems/insufficient-funds",
    title: "Conflict",
    status: 409,
    detail: "Account 100100000018 has insufficient funds",
    code: "INSUFFICIENT_FUNDS",
    timestamp: "2026-03-15T12:00:00Z",
    ...overrides,
  };
}

afterEach(cleanup);

describe("ErrorBanner", () => {
  it("renders nothing when there is no error", () => {
    const { container } = render(<ErrorBanner error={undefined} />);
    expect(container.firstChild).toBeNull();
  });

  it("shows the backend's problem detail for an ApiError", () => {
    render(<ErrorBanner error={new ApiError(problem(), 409)} />);
    expect(screen.getByText("Account 100100000018 has insufficient funds")).toBeTruthy();
  });

  it("shows the message for an ordinary Error", () => {
    render(<ErrorBanner error={new Error("Network request failed")} />);
    expect(screen.getByText("Network request failed")).toBeTruthy();
  });

  it("falls back to a generic line when the failure carries no message", () => {
    // A rejected fetch can surface as an Error with an empty message; showing an empty red bar
    // tells the user nothing at all.
    render(<ErrorBanner error={new Error("")} />);
    expect(screen.getByText("Something went wrong.")).toBeTruthy();
  });
});

describe("fieldErrors", () => {
  it("unwraps per-field validation errors from an ApiError", () => {
    const validation = problem({
      code: "VALIDATION_FAILED",
      errors: { amount: "must be at least 0.01" },
    });
    expect(fieldErrors(new ApiError(validation, 400))).toEqual({ amount: "must be at least 0.01" });
  });

  it("returns nothing for an ApiError without field errors, or a plain Error", () => {
    expect(fieldErrors(new ApiError(problem(), 409))).toBeUndefined();
    expect(fieldErrors(new Error("boom"))).toBeUndefined();
  });
});
