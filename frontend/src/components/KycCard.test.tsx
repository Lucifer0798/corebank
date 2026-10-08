import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useKycDecisions, useUpdateKyc } from "../api/hooks";
import type { KycDecision, PagedResponse } from "../api/types";
import { KycCard, kycReasonRequired } from "./KycCard";

/**
 * The two things this card decides: that a restricting decision cannot be sent without a reason,
 * and that only an admin gets to make one. The history itself is the backend's; this only shows it.
 */
vi.mock("../api/hooks", () => ({ useKycDecisions: vi.fn(), useUpdateKyc: vi.fn() }));

const CUSTOMER_ID = "cccccccc-0000-4000-8000-000000000001";
const mutate = vi.fn();

function decision(overrides: Partial<KycDecision> = {}): KycDecision {
  return {
    id: "dddddddd-0000-4000-8000-000000000001",
    fromStatus: "VERIFIED",
    toStatus: "REJECTED",
    decidedBySubject: "admin-subject",
    decidedByName: "priya",
    reason: "Address could not be confirmed on re-check",
    decidedAt: "2026-10-08T09:00:00Z",
    ...overrides,
  };
}

function history(content: KycDecision[]) {
  vi.mocked(useKycDecisions).mockReturnValue({
    data: {
      content,
      page: 0,
      size: 10,
      totalElements: content.length,
      totalPages: 1,
      last: true,
    } satisfies PagedResponse<KycDecision>,
    error: null,
  } as unknown as ReturnType<typeof useKycDecisions>);
}

beforeEach(() => {
  mutate.mockClear();
  vi.mocked(useUpdateKyc).mockReturnValue({
    mutate,
    isPending: false,
    error: null,
  } as unknown as ReturnType<typeof useUpdateKyc>);
  history([]);
});

afterEach(cleanup);

const submit = () => screen.getByRole("button", { name: "Record decision" });

describe("KycCard", () => {
  it("will not send a rejection without a reason", () => {
    render(<KycCard customerId={CUSTOMER_ID} current="VERIFIED" admin />);

    fireEvent.change(screen.getByLabelText("New status"), { target: { value: "REJECTED" } });
    expect((submit() as HTMLButtonElement).disabled).toBe(true);

    // Whitespace is not a reason, here or on the backend.
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "   " } });
    expect((submit() as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "Sanctions list match" } });
    fireEvent.click(submit());
    expect(mutate).toHaveBeenCalledWith(
      { kycStatus: "REJECTED", reason: "Sanctions list match" },
      expect.anything(),
    );
  });

  it("lets a verification through without a reason", () => {
    render(<KycCard customerId={CUSTOMER_ID} current="PENDING" admin />);

    fireEvent.change(screen.getByLabelText("New status"), { target: { value: "VERIFIED" } });
    fireEvent.click(submit());

    expect(mutate).toHaveBeenCalledWith({ kycStatus: "VERIFIED", reason: undefined }, expect.anything());
  });

  it("never offers the status the customer already has", () => {
    render(<KycCard customerId={CUSTOMER_ID} current="VERIFIED" admin />);

    const options = Array.from((screen.getByLabelText("New status") as HTMLSelectElement).options)
      .map((option) => option.value);
    expect(options).toEqual(["PENDING", "REJECTED"]);
  });

  it("shows a teller the history but no way to decide", () => {
    history([decision()]);
    render(<KycCard customerId={CUSTOMER_ID} current="REJECTED" admin={false} />);

    expect(screen.queryByRole("button", { name: "Record decision" })).toBeNull();
    expect(screen.getByText("priya")).toBeTruthy();
    expect(screen.getByText("Address could not be confirmed on re-check")).toBeTruthy();
  });

  it("names a system decider, and says plainly when there is no history yet", () => {
    history([decision({ decidedBySubject: "system:dev-data-seeder", decidedByName: "dev-data-seeder", reason: null })]);
    const { unmount } = render(<KycCard customerId={CUSTOMER_ID} current="VERIFIED" admin={false} />);
    expect(screen.getByText("dev-data-seeder")).toBeTruthy();
    unmount();

    history([]);
    render(<KycCard customerId={CUSTOMER_ID} current="PENDING" admin={false} />);
    expect(screen.getByText(/earlier decisions were never kept/)).toBeTruthy();
  });
});

describe("kycReasonRequired", () => {
  it("asks for a reason on anything that restricts the customer", () => {
    expect(kycReasonRequired("REJECTED")).toBe(true);
    expect(kycReasonRequired("PENDING")).toBe(true);
    expect(kycReasonRequired("VERIFIED")).toBe(false);
  });
});
