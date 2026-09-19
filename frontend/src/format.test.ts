import { describe, expect, it } from "vitest";
import { formatAmount, formatDate, formatDateTime } from "./format";

/**
 * formatAmount memoises one Intl.NumberFormat per currency. That cache is the part worth
 * testing: keyed wrongly, the second currency silently renders with the first one's symbol, and
 * every amount on the page would look right while being labelled as the wrong money.
 *
 * <p>Assertions check the symbol and the grouped digits separately rather than matching a whole
 * formatted string. Intl output varies by ICU version (which space character sits between symbol
 * and digits, most commonly), so an exact-match assertion would pass here and fail on a runner
 * with a different Node build -- a flaky test being worse than no test.
 */
describe("formatAmount", () => {
  it("formats rupees with Indian digit grouping by default", () => {
    const formatted = formatAmount(123456.5);
    expect(formatted).toContain("₹");
    // en-IN groups as 1,23,456.50 rather than 123,456.50 -- the whole reason the locale is pinned.
    expect(formatted).toContain("1,23,456.50");
  });

  it("always shows two decimal places", () => {
    expect(formatAmount(10)).toContain("10.00");
    expect(formatAmount(0)).toContain("0.00");
  });

  it("formats a negative amount as negative", () => {
    expect(formatAmount(-42.5)).toContain("42.50");
    expect(formatAmount(-42.5)).toContain("-");
  });

  it("keeps one formatter per currency rather than reusing the first", () => {
    // Order matters: INR primes the cache, so a cache keyed on anything but the currency would
    // hand the same formatter back for USD and render dollars with a rupee sign.
    const rupees = formatAmount(1000, "INR");
    const dollars = formatAmount(1000, "USD");
    const rupeesAgain = formatAmount(1000, "INR");

    expect(rupees).toContain("₹");
    expect(dollars).toContain("$");
    expect(dollars).not.toContain("₹");
    expect(rupeesAgain).toBe(rupees);
  });
});

/**
 * The date helpers read the host timezone, so an exact expected string would depend on where the
 * test runs -- IST locally, UTC in CI. These pin the timezone-independent parts instead: a
 * midday-UTC instant lands on the same calendar day everywhere this would plausibly run, and an
 * unparseable input has to degrade rather than throw.
 */
describe("formatDate and formatDateTime", () => {
  const middayUtc = "2026-03-15T12:00:00Z";

  it("renders a readable date", () => {
    const formatted = formatDate(middayUtc);
    expect(formatted).toContain("2026");
    expect(formatted).toContain("15");
  });

  it("renders a date and a time together", () => {
    const formatted = formatDateTime(middayUtc);
    expect(formatted).toContain("2026");
    // dateStyle: medium + timeStyle: short always yields both halves, so the string is longer
    // than the date alone -- the cheapest assertion that the time half didn't get dropped.
    expect(formatted.length).toBeGreaterThan(formatDate(middayUtc).length);
  });

  it("degrades rather than throwing on an unparseable timestamp", () => {
    expect(() => formatDateTime("not a timestamp")).not.toThrow();
    expect(formatDateTime("not a timestamp")).toBe("Invalid Date");
  });
});
