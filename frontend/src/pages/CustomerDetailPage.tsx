import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import {
  useAccountsForCustomer,
  useCustomer,
  useLinkIdentity,
  useOpenAccount,
  useUpdateKyc,
} from "../api/hooks";
import { ErrorBanner } from "../components/ErrorBanner";
import { SpendingInsights } from "../components/SpendingInsights";
import { StatusPill } from "../components/StatusPill";
import { formatAmount } from "../format";
import { rolesFromAccessToken } from "../auth/roles";

export function CustomerDetailPage() {
  const { customerId } = useParams<{ customerId: string }>();
  const auth = useAuth();
  const roles = rolesFromAccessToken(auth.user?.access_token);
  const isAdmin = roles.includes("ADMIN");

  const { data: customer, isLoading, error } = useCustomer(customerId);
  const { data: accounts } = useAccountsForCustomer(customerId);
  const updateKyc = useUpdateKyc(customerId!);
  const [showOpenAccount, setShowOpenAccount] = useState(false);

  if (isLoading) return <p className="muted">Loading&hellip;</p>;
  if (error || !customer) return <ErrorBanner error={error} />;

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
        <h3>Profile</h3>
        <div className="stat-grid">
          <div className="stat">
            <div className="stat__label">KYC status</div>
            <div><StatusPill status={customer.kycStatus} /></div>
          </div>
          <div className="stat">
            <div className="stat__label">Customer status</div>
            <div><StatusPill status={customer.status} /></div>
          </div>
          <div className="stat">
            <div className="stat__label">Identity</div>
            <div>{customer.identityLinked ? <StatusPill status="ACTIVE" /> : <StatusPill status="PENDING" />}</div>
          </div>
        </div>
        <p className="muted" style={{ marginTop: "0.75rem" }}>
          {customer.email} {customer.phone ? `· ${customer.phone}` : ""}
        </p>

        {isAdmin && customer.kycStatus === "PENDING" && (
          <div className="btn-row" style={{ marginTop: "0.75rem" }}>
            <ErrorBanner error={updateKyc.error} />
            <button className="btn" onClick={() => updateKyc.mutate("VERIFIED")} disabled={updateKyc.isPending}>
              Verify KYC
            </button>
            <button
              className="btn btn--danger"
              onClick={() => updateKyc.mutate("REJECTED")}
              disabled={updateKyc.isPending}
            >
              Reject KYC
            </button>
          </div>
        )}
      </div>

      {!customer.identityLinked && <LinkIdentityCard customerId={customer.id} />}

      <div className="card">
        <div className="page-header" style={{ marginBottom: "0.5rem" }}>
          <h3 style={{ margin: 0 }}>Accounts</h3>
          {customer.kycStatus === "VERIFIED" && customer.status === "ACTIVE" && (
            <button className="btn btn--secondary" onClick={() => setShowOpenAccount((v) => !v)}>
              {showOpenAccount ? "Cancel" : "Open account"}
            </button>
          )}
        </div>
        {showOpenAccount && (
          <OpenAccountForm customerId={customer.id} onDone={() => setShowOpenAccount(false)} />
        )}
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

      <SpendingInsights customerId={customer.id} />
    </>
  );
}

function LinkIdentityCard({ customerId }: { customerId: string }) {
  const linkIdentity = useLinkIdentity(customerId);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const subject = new FormData(event.currentTarget).get("keycloakSubject");
    linkIdentity.mutate(String(subject));
  }

  return (
    <div className="card">
      <h3>Link a Keycloak identity</h3>
      <p className="muted">
        Required before this customer can sign in and see their own accounts. Find the identity's
        id in the Keycloak admin console under the user's Details tab as &ldquo;ID&rdquo;.
      </p>
      <ErrorBanner error={linkIdentity.error} />
      <form className="form" onSubmit={handleSubmit}>
        <div className="form-row">
          <label htmlFor="keycloakSubject">Keycloak subject (ID)</label>
          <input id="keycloakSubject" name="keycloakSubject" required maxLength={64}
            placeholder="00000000-0000-4000-8000-000000000003" />
        </div>
        <div className="btn-row">
          <button className="btn" type="submit" disabled={linkIdentity.isPending}>
            {linkIdentity.isPending ? "Linking…" : "Link identity"}
          </button>
        </div>
      </form>
    </div>
  );
}

function OpenAccountForm({ customerId, onDone }: { customerId: string; onDone: () => void }) {
  const openAccount = useOpenAccount();
  const [accountType, setAccountType] = useState<"SAVINGS" | "CURRENT">("SAVINGS");

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const overdraft = form.get("overdraftLimit");
    openAccount.mutate(
      {
        customerId,
        accountType,
        overdraftLimit: overdraft ? Number(overdraft) : undefined,
      },
      { onSuccess: onDone },
    );
  }

  return (
    <div style={{ marginBottom: "1rem" }}>
      <ErrorBanner error={openAccount.error} />
      <form className="form" onSubmit={handleSubmit}>
        <div className="form-row">
          <label htmlFor="accountType">Account type</label>
          <select
            id="accountType"
            value={accountType}
            onChange={(e) => setAccountType(e.target.value as "SAVINGS" | "CURRENT")}
          >
            <option value="SAVINGS">Savings</option>
            <option value="CURRENT">Current</option>
          </select>
        </div>
        {accountType === "CURRENT" && (
          <div className="form-row">
            <label htmlFor="overdraftLimit">Overdraft limit (optional)</label>
            <input id="overdraftLimit" name="overdraftLimit" type="number" min={0} step="0.01" />
          </div>
        )}
        <div className="btn-row">
          <button className="btn" type="submit" disabled={openAccount.isPending}>
            {openAccount.isPending ? "Opening…" : "Open account"}
          </button>
        </div>
      </form>
    </div>
  );
}
