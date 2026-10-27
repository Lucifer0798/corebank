import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useFxPosition, useRevalue } from "../api/hooks";
import type { FxPositionReport } from "../api/types";
import { FxBookCard } from "./FxBookCard";

/**
 * The bank's FX book and its close, which used to be API-only. A close that finds nothing to post
 * is a normal outcome and must read as one.
 */
vi.mock("../api/hooks", () => ({ useFxPosition: vi.fn(), useRevalue: vi.fn() }));

const mutate = vi.fn();

function report(overrides: Partial<FxPositionReport> = {}): FxPositionReport {
  return {
    reportingCurrency: "INR",
    positions: [
      { currency: "INR", accountNumber: "GL0000000010", balance: 250000, rateToReporting: 1, valueInReportingCurrency: 250000 },
      { currency: "USD", accountNumber: "GL0000000011", balance: -2985, rateToReporting: 83.4, valueInReportingCurrency: -248949 },
    ],
    markToMarket: 1051,
    markCarried: 1000,
    asOf: "2026-10-27T09:00:00Z",
    ...overrides,
  };
}

function revalueState(overrides: Record<string, unknown> = {}) {
  vi.mocked(useRevalue).mockReturnValue(
    { mutate, isPending: false, isSuccess: false, error: null, data: undefined, ...overrides } as unknown as ReturnType<typeof useRevalue>);
}

function renderCard() {
  return render(<MemoryRouter><FxBookCard /></MemoryRouter>);
}

beforeEach(() => {
  mutate.mockClear();
  vi.mocked(useFxPosition).mockReturnValue({ data: report(), error: null } as unknown as ReturnType<typeof useFxPosition>);
  revalueState();
});

afterEach(cleanup);

describe("FxBookCard", () => {
  it("lists each currency's position, marks a short one, and states what a close would recognise", () => {
    renderCard();

    expect(screen.getByText("USD")).toBeTruthy();
    expect(screen.getByText("(short)")).toBeTruthy();
    expect(screen.getByText(/A gain of/).textContent).toContain("51.00");
  });

  it("says a loss is a loss", () => {
    vi.mocked(useFxPosition).mockReturnValue(
      { data: report({ markToMarket: 900, markCarried: 1000 }), error: null } as unknown as ReturnType<typeof useFxPosition>);
    renderCard();

    expect(screen.getByText(/A loss of/).textContent).toContain("100.00");
  });

  it("revalues, and links the posting it made", () => {
    revalueState({ isSuccess: true, data: { reference: "FXR-20261027-0001", markCarried: 1051 } });
    renderCard();

    fireEvent.click(screen.getByRole("button", { name: "Revalue" }));
    expect(mutate).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("link", { name: "FXR-20261027-0001" }).getAttribute("href"))
      .toBe("/transactions/FXR-20261027-0001");
  });

  it("reads a close with nothing to post as nothing to do, not as a failure", () => {
    revalueState({ isSuccess: true, data: { reference: null, markCarried: 1000 } });
    renderCard();

    expect(screen.getByText(/Nothing to do/)).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
  });
});
