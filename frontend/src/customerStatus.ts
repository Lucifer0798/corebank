import type { CustomerStatus } from "./api/types";

export type CustomerStatusAction = "suspend" | "reinstate" | "close";

/**
 * What can be done to a customer in this status, by this member of staff. Every change is admin-only
 * on the backend, so a teller is offered nothing that could only produce a 403, and a closed customer
 * nothing at all: closing is final.
 */
export function customerStatusActionsFor(status: CustomerStatus, admin: boolean): CustomerStatusAction[] {
  if (!admin || status === "CLOSED") {
    return [];
  }
  return [status === "ACTIVE" ? "suspend" : "reinstate", "close"];
}

/** Suspending and closing need a reason; the backend refuses them without one. Reinstating does not. */
export function customerReasonRequired(action: CustomerStatusAction): boolean {
  return action !== "reinstate";
}

/** The backend refuses a KYC decision on a closed customer, so the form should not be offered. */
export function canReviewKyc(status: CustomerStatus): boolean {
  return status !== "CLOSED";
}
