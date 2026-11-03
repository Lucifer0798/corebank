import type { Account } from "./api/types";

/**
 * The interest a pay-out would pay now: whole paise, rounded down, as the backend's capitalisation
 * does. Counted in ten-thousandths first, since the accrued figure has four places and a double does
 * not: 0.29 * 100 is 28.999999999999996, and rounding down straight from there names a paisa less
 * than the backend would pay.
 */
export function payableInterest(accrued: number): number {
  return Math.floor(Math.round(accrued * 10000) / 100) / 100;
}

/**
 * Whether paying interest out can do anything. A closed account is never paid into, and less than
 * a paisa is refused as nothing to pay -- neither is worth a button that can only fail.
 */
export function canPayOutInterest(account: Pick<Account, "status" | "accruedInterest">): boolean {
  return account.status !== "CLOSED" && payableInterest(account.accruedInterest) >= 0.01;
}
