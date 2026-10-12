import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useIdentityLinks, useRelinkIdentity } from "../api/hooks";
import type { IdentityLink, PagedResponse } from "../api/types";
import { IdentityCard } from "./IdentityCard";

/**
 * Who may replace a customer's linked login (an admin, and only once one exists), and that the
 * replacement cannot be sent without a reason.
 */
vi.mock("../api/hooks", () => ({ useIdentityLinks: vi.fn(), useRelinkIdentity: vi.fn() }));

const CUSTOMER_ID = "cccccccc-0000-4000-8000-000000000001";
const mutate = vi.fn();

function history(content: IdentityLink[]) {
  vi.mocked(useIdentityLinks).mockReturnValue({
    data: {
      content,
      page: 0,
      size: 10,
      totalElements: content.length,
      totalPages: 1,
      last: true,
    } satisfies PagedResponse<IdentityLink>,
    error: null,
  } as unknown as ReturnType<typeof useIdentityLinks>);
}

beforeEach(() => {
  mutate.mockClear();
  vi.mocked(useRelinkIdentity).mockReturnValue({
    mutate,
    isPending: false,
    error: null,
  } as unknown as ReturnType<typeof useRelinkIdentity>);
  history([]);
});

afterEach(cleanup);

const replace = () => screen.getByRole("button", { name: "Replace identity" }) as HTMLButtonElement;

describe("IdentityCard", () => {
  it("will not replace an identity without a reason", () => {
    render(<IdentityCard customerId={CUSTOMER_ID} linked admin />);

    fireEvent.change(screen.getByLabelText(/New Keycloak subject/), { target: { value: "new-subject" } });
    expect(replace().disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "   " } });
    expect(replace().disabled).toBe(true);

    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "Keycloak account recreated" } });
    fireEvent.click(replace());
    expect(mutate).toHaveBeenCalledWith(
      { keycloakSubject: "new-subject", reason: "Keycloak account recreated" },
      expect.anything(),
    );
  });

  it("offers a teller the history but no way to replace", () => {
    render(<IdentityCard customerId={CUSTOMER_ID} linked admin={false} />);

    expect(screen.queryByRole("button", { name: "Replace identity" })).toBeNull();
  });

  it("offers no replacement while there is nothing linked to replace", () => {
    // A first link is LinkIdentityCard's job; the backend would refuse a relink with CUSTOMER_NOT_LINKED.
    render(<IdentityCard customerId={CUSTOMER_ID} linked={false} admin />);

    expect(screen.queryByRole("button", { name: "Replace identity" })).toBeNull();
  });

  it("shows what each link replaced, and marks a first link as one", () => {
    history([
      {
        id: "ffffffff-0000-4000-8000-000000000002",
        previousSubject: "old-subject",
        newSubject: "new-subject",
        linkedBySubject: "admin-subject",
        linkedByName: "priya",
        reason: "Keycloak account recreated",
        linkedAt: "2026-10-11T10:00:00Z",
      },
      {
        id: "ffffffff-0000-4000-8000-000000000001",
        previousSubject: null,
        newSubject: "old-subject",
        linkedBySubject: "teller-subject",
        linkedByName: "kavya",
        reason: null,
        linkedAt: "2026-10-01T10:00:00Z",
      },
    ]);
    render(<IdentityCard customerId={CUSTOMER_ID} linked admin={false} />);

    expect(screen.getByText("— (first link)")).toBeTruthy();
    expect(screen.getAllByText("old-subject")).toHaveLength(2);
    expect(screen.getByText("Keycloak account recreated")).toBeTruthy();
  });
});
