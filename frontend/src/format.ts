const CURRENCY_FORMATTERS = new Map<string, Intl.NumberFormat>();

export function formatAmount(amount: number, currency = "INR"): string {
  let formatter = CURRENCY_FORMATTERS.get(currency);
  if (!formatter) {
    formatter = new Intl.NumberFormat("en-IN", { style: "currency", currency });
    CURRENCY_FORMATTERS.set(currency, formatter);
  }
  return formatter.format(amount);
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-IN", { dateStyle: "medium" });
}

/**
 * Renders a calendar date -- a backend {@code LocalDate}, "2026-10-01" with no time and no zone.
 *
 * <p>{@link formatDate} is wrong for these, and wrong in a way that only shows up for some of the
 * people using it. `new Date("2026-10-01")` is specified to parse a date-only string as UTC
 * midnight, and rendering that in a zone behind UTC lands on 30 September. A standing order due
 * on the 1st would read as the 31st to a viewer in New York while looking correct in Mumbai.
 * Splitting the parts and building a local date keeps the day the backend said it was.
 *
 * <p>Anything that is not a plain YYYY-MM-DD falls through to {@link formatDate}, so a full
 * instant handed here by mistake still renders rather than breaking the page.
 */
export function formatCalendarDate(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) {
    return formatDate(date);
  }
  const [, year, month, day] = match;
  return new Date(Number(year), Number(month) - 1, Number(day))
    .toLocaleDateString("en-IN", { dateStyle: "medium" });
}
