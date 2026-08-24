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
