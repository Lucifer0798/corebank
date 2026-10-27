import type { FxPositionReport } from "./api/types";

/**
 * What a close would recognise: the book's current mark less what it is carried at. Rounded to the
 * money scale, because the two figures are already money and their difference should read as such
 * rather than as 34.560000000001.
 */
export function unrecognised(report: Pick<FxPositionReport, "markToMarket" | "markCarried">): number {
  return Math.round((report.markToMarket - report.markCarried) * 100) / 100;
}

/** "A gain of", "a loss of", or nothing to recognise. */
export function describeUnrecognised(amount: number): "gain" | "loss" | "none" {
  if (amount > 0) {
    return "gain";
  }
  return amount < 0 ? "loss" : "none";
}
