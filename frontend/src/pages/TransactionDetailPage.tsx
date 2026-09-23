import { type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { useReverseTransaction, useTransaction } from "../api/hooks";
import type { Transaction } from "../api/types";
import { ErrorBanner } from "../components/ErrorBanner";
import { StatusPill } from "../components/StatusPill";
import { formatAmount, formatDateTime } from "../format";
import { isAdmin, rolesFromAccessToken } from "../auth/roles";
import { reversalAvailability } from "../reversal";

export function TransactionDetailPage() {
  const { reference } = useParams<{ reference: string }>();
  const auth = useAuth();
  const admin = isAdmin(rolesFromAccessToken(auth.user?.access_token));
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
        {transaction.reversalOf && (
          <p className="muted" style={{ marginTop: "0.75rem" }}>
            Reverses <Link to={`/transactions/${transaction.reversalOf}`}>{transaction.reversalOf}</Link>
          </p>
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

      {admin && <ReversalCard transaction={transaction} />}
    </>
  );
}

/**
 * Admin-only, and hidden outright rather than disabled for anyone else -- a teller has no route
 * to this operation at all, so offering a greyed-out button would only suggest otherwise.
 *
 * When the posting cannot be reversed the card still renders, with the reason in place of the
 * form. That is deliberate: "why is there no Reverse button here" is the question an admin would
 * otherwise have to answer by reading the backend.
 */
function ReversalCard({ transaction }: { transaction: Transaction }) {
  const reverse = useReverseTransaction(transaction.reference);
  const availability = reversalAvailability(transaction);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const reason = String(new FormData(event.currentTarget).get("reason") ?? "").trim();
    reverse.mutate({ reason });
  }

  return (
    <div className="card">
      <h3>Reverse</h3>

      {/* Success is checked before availability on purpose. The moment this succeeds the original
          refetches as REVERSED, so an availability-first branch would replace the confirmation --
          and the link to the correction that was just posted -- with "already been reversed". */}
      {reverse.isSuccess ? (
        <p style={{ color: "var(--color-success)" }}>
          Reversed by{" "}
          <Link to={`/transactions/${reverse.data.reference}`}>{reverse.data.reference}</Link>.
        </p>
      ) : !availability.available ? (
        <p className="muted">{availability.reason}</p>
      ) : (
        <>
          <p className="muted">
            Posts a new transaction mirroring every leg of this one. Nothing is edited or erased:
            both postings stay on the statement. It may take an account past its overdraft limit,
            and it cannot be undone.
          </p>
          <ErrorBanner error={reverse.error} />
          <form className="form" onSubmit={handleSubmit}>
            <div className="form-row">
              <label htmlFor="reason">Reason</label>
              <input
                id="reason"
                name="reason"
                required
                maxLength={255}
                placeholder="Duplicate counter deposit keyed twice by branch 004"
              />
            </div>
            <button className="btn btn--danger" type="submit" disabled={reverse.isPending}>
              {reverse.isPending ? "Reversing…" : "Reverse this transaction"}
            </button>
          </form>
        </>
      )}
    </div>
  );
}
