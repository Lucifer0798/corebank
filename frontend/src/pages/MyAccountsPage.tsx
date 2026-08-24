import { Link } from "react-router-dom";
import { useAccountsForCustomer, useMyCustomer } from "../api/hooks";
import { ApiError } from "../api/client";
import { ErrorBanner } from "../components/ErrorBanner";
import { StatusPill } from "../components/StatusPill";
import { formatAmount } from "../format";

export function MyAccountsPage() {
  const { data: customer, isLoading, error } = useMyCustomer();
  const { data: accounts } = useAccountsForCustomer(customer?.id);

  if (isLoading) {
    return <p className="muted">Loading&hellip;</p>;
  }

  if (error instanceof ApiError && error.status === 404) {
    return (
      <div className="card">
        <h2>Your account isn&rsquo;t set up yet</h2>
        <p className="muted">
          Your sign-in works, but it hasn&rsquo;t been linked to a customer record yet. Contact
          your branch and ask them to complete that step.
        </p>
      </div>
    );
  }

  if (error || !customer) {
    return <ErrorBanner error={error} />;
  }

  return (
    <>
      <div className="page-header">
        <div>
          <h1>
            {customer.firstName} {customer.lastName}
          </h1>
          <p className="muted">{customer.customerNumber}</p>
        </div>
      </div>

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Account</th>
              <th>Type</th>
              <th>Balance</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {accounts?.content.map((account) => (
              <tr key={account.id}>
                <td>
                  <Link to={`/accounts/${account.id}`}>{account.accountNumber}</Link>
                </td>
                <td>{account.accountType}</td>
                <td className="amount">{formatAmount(account.balance, account.currency)}</td>
                <td><StatusPill status={account.status} /></td>
              </tr>
            ))}
            {accounts && accounts.content.length === 0 && (
              <tr>
                <td colSpan={4} className="muted">No accounts yet.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
