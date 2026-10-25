import { useRecentEntries, useSpendingSummary } from "../api/insightsHooks";
import type { CategorySpend } from "../api/insightsTypes";
import { formatAmount, formatDateTime } from "../format";
import { ErrorBanner } from "./ErrorBanner";

/** Category spend + recent categorised entries for one customer, from the separate Python
 * insights service. Shared between CustomerDetailPage (staff, viewing anyone) and
 * MyAccountsPage (a customer viewing their own) -- both pass the same customerId shape. */
export function SpendingInsights({ customerId }: { customerId: string }) {
  const { data: summary, isLoading, error } = useSpendingSummary(customerId);
  const { data: entries } = useRecentEntries(customerId, 10);

  return (
    <div className="card">
      <h3>Spending insights</h3>
      <p className="muted">
        Categorised automatically from posted transactions. A projection of the ledger, not the
        ledger itself -- it can lag a live posting by a few seconds.
      </p>
      <ErrorBanner error={error} />
      {isLoading && <p className="muted">Loading&hellip;</p>}

      {summary && summary.accounts.length === 0 && (
        <p className="muted">No accounts to summarise yet.</p>
      )}

      {summary && summary.accounts.length > 0 && (
        <>
          {/* One total per currency. A single figure used to add a dollar account's spending to a
              rupee account's and call the sum rupees. */}
          <div className="stat-grid" style={{ marginBottom: "1rem" }}>
            {summary.totals.map((total) => (
              <div className="stat" key={total.currency}>
                <div className="stat__label">Total spent ({total.currency})</div>
                <div className="stat__value">{formatAmount(Number(total.spent), total.currency)}</div>
              </div>
            ))}
          </div>

          {summary.categories.length === 0 ? (
            <p className="muted">No spending recorded yet.</p>
          ) : (
            summary.totals.map((total) => (
              <CategoryBreakdown
                key={total.currency}
                currency={total.currency}
                total={Number(total.spent)}
                categories={summary.categories.filter((c) => c.currency === total.currency)}
                labelled={summary.totals.length > 1}
              />
            ))
          )}
        </>
      )}

      {entries && entries.length > 0 && (
        <>
          <h3 style={{ marginTop: "1.5rem" }}>Recent entries</h3>
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Account</th>
                <th>Category</th>
                <th>Description</th>
                <th>Amount</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.reference + entry.account_number}>
                  <td className="muted">{formatDateTime(entry.posted_at)}</td>
                  <td className="muted">{entry.account_number}</td>
                  <td>{entry.category}</td>
                  <td className="muted">{entry.description ?? "—"}</td>
                  <td
                    className={`amount ${Number(entry.signed_amount) < 0 ? "amount--negative" : "amount--positive"}`}
                  >
                    {formatAmount(Number(entry.signed_amount), entry.currency)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

/** One currency's categories, each bar a share of that currency's own total. */
function CategoryBreakdown({ currency, total, categories, labelled }: {
  currency: string;
  total: number;
  categories: CategorySpend[];
  labelled: boolean;
}) {
  const denominator = total || 1;
  return (
    <div className="category-breakdown">
      {labelled && <h4 className="muted">{currency}</h4>}
      {categories
        .slice()
        .sort((a, b) => Number(b.spent) - Number(a.spent))
        .map((category) => {
          const share = Math.min(100, (Number(category.spent) / denominator) * 100);
          return (
            <div className="category-row" key={category.category + category.currency}>
              <div className="category-row__label">
                <span>{category.category}</span>
                <span className="muted">
                  {formatAmount(Number(category.spent), category.currency)} &middot; {category.entries}{" "}
                  {category.entries === 1 ? "entry" : "entries"}
                </span>
              </div>
              <div className="category-row__track">
                <div className="category-row__fill" style={{ width: `${share}%` }} />
              </div>
            </div>
          );
        })}
    </div>
  );
}
