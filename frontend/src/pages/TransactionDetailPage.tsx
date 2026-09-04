import { useParams } from "react-router-dom";
import { useTransaction } from "../api/hooks";
import { ErrorBanner } from "../components/ErrorBanner";
import { StatusPill } from "../components/StatusPill";
import { formatAmount, formatDateTime } from "../format";

export function TransactionDetailPage() {
  const { reference } = useParams<{ reference: string }>();
  const { data: transaction, isLoading, error } = useTransaction(reference);

  if (isLoading) return <p className="muted">Loading&hellip;</p>;
  if (error || !transaction) return <ErrorBanner error={error} />;

  return (
    <>
      <div className="page-header">
        <div>
          <h1>{transaction.reference}</h1>
          <p className="muted">
            {transaction.type} &middot; {formatDateTime(transaction.postedAt)}
          </p>
        </div>
        <StatusPill status={transaction.status} />
      </div>

      <div className="card">
        <div className="stat-grid">
          <div className="stat">
            <div className="stat__label">Amount</div>
            <div className="stat__value">{formatAmount(transaction.amount, transaction.currency)}</div>
          </div>
        </div>
        {transaction.description && (
          <p className="muted" style={{ marginTop: "0.75rem" }}>{transaction.description}</p>
        )}
      </div>

      <div className="card">
        <h3>Ledger legs</h3>
        <table>
          <thead>
            <tr>
              <th>Account</th>
              <th>Direction</th>
              <th>Amount</th>
              <th>Balance after</th>
            </tr>
          </thead>
          <tbody>
            {transaction.legs.map((leg) => (
              <tr key={leg.accountId}>
                <td>{leg.accountNumber}</td>
                <td>{leg.direction}</td>
                <td className="amount">{formatAmount(leg.amount, transaction.currency)}</td>
                <td className="amount">{formatAmount(leg.balanceAfter, transaction.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
