import { useState, type FormEvent } from "react";
import { useParams } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import {
  useAccount,
  useBalance,
  useCloseAccount,
  useDeposit,
  useFreezeAccount,
  useStatement,
  useTransfer,
  useUnfreezeAccount,
  useWithdraw,
} from "../api/hooks";
import { ErrorBanner } from "../components/ErrorBanner";
import { StatusPill } from "../components/StatusPill";
import { formatAmount, formatDateTime } from "../format";
import { isStaff, rolesFromAccessToken } from "../auth/roles";

export function AccountDetailPage() {
  const { accountId } = useParams<{ accountId: string }>();
  const auth = useAuth();
  const staff = isStaff(rolesFromAccessToken(auth.user?.access_token));

  const { data: account, isLoading, error } = useAccount(accountId);
  const { data: balance } = useBalance(accountId);
  const [page, setPage] = useState(0);
  const { data: statement } = useStatement(accountId, page);

  const freeze = useFreezeAccount();
  const unfreeze = useUnfreezeAccount();
  const close = useCloseAccount();

  if (isLoading) return <p className="muted">Loading&hellip;</p>;
  if (error || !account) return <ErrorBanner error={error} />;

  return (
    <>
      <div className="page-header">
        <div>
          <h1>{account.accountNumber}</h1>
          <p className="muted">
            {account.accountType} account &middot; {account.currency}
          </p>
        </div>
        <StatusPill status={account.status} />
      </div>

      <div className="card">
        <div className="stat-grid">
          <div className="stat">
            <div className="stat__label">Balance</div>
            <div className="stat__value">{formatAmount(balance?.balance ?? account.balance, account.currency)}</div>
          </div>
          <div className="stat">
            <div className="stat__label">Available</div>
            <div className="stat__value">
              {formatAmount(balance?.availableBalance ?? account.availableBalance, account.currency)}
            </div>
          </div>
          {account.overdraftLimit > 0 && (
            <div className="stat">
              <div className="stat__label">Overdraft limit</div>
              <div className="stat__value">{formatAmount(account.overdraftLimit, account.currency)}</div>
            </div>
          )}
        </div>

        {staff && account.status !== "CLOSED" && (
          <div className="btn-row" style={{ marginTop: "1rem" }}>
            <ErrorBanner error={freeze.error || unfreeze.error || close.error} />
            {account.status === "ACTIVE" ? (
              <button className="btn btn--secondary" onClick={() => freeze.mutate(account.id)} disabled={freeze.isPending}>
                Freeze
              </button>
            ) : (
              <button className="btn btn--secondary" onClick={() => unfreeze.mutate(account.id)} disabled={unfreeze.isPending}>
                Unfreeze
              </button>
            )}
            <button className="btn btn--danger" onClick={() => close.mutate(account.id)} disabled={close.isPending}>
              Close account
            </button>
          </div>
        )}
      </div>

      {staff && account.status !== "CLOSED" && <MoneyMovementCard accountId={account.id} />}

      <div className="card">
        <h3>Statement</h3>
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Type</th>
              <th>Reference</th>
              <th>Amount</th>
              <th>Balance after</th>
            </tr>
          </thead>
          <tbody>
            {statement?.content.map((line) => (
              <tr key={line.entryId}>
                <td className="muted">{formatDateTime(line.postedAt)}</td>
                <td>{line.type}</td>
                <td className="muted">{line.reference}</td>
                <td className={`amount ${line.signedAmount < 0 ? "amount--negative" : "amount--positive"}`}>
                  {formatAmount(line.signedAmount, account.currency)}
                </td>
                <td className="amount">{formatAmount(line.balanceAfter, account.currency)}</td>
              </tr>
            ))}
            {statement && statement.content.length === 0 && (
              <tr>
                <td colSpan={5} className="muted">No transactions yet.</td>
              </tr>
            )}
          </tbody>
        </table>
        <div className="btn-row" style={{ marginTop: "1rem" }}>
          <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </button>
          <button className="btn btn--secondary" disabled={statement?.last} onClick={() => setPage((p) => p + 1)}>
            Next
          </button>
        </div>
      </div>
    </>
  );
}

function MoneyMovementCard({ accountId }: { accountId: string }) {
  const [tab, setTab] = useState<"deposit" | "withdraw" | "transfer">("deposit");
  const deposit = useDeposit(accountId);
  const withdraw = useWithdraw(accountId);
  const transfer = useTransfer();

  function handleDeposit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    deposit.mutate({
      amount: Number(form.get("amount")),
      description: String(form.get("description") || "") || undefined,
    });
    event.currentTarget.reset();
  }

  function handleWithdraw(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    withdraw.mutate({
      amount: Number(form.get("amount")),
      description: String(form.get("description") || "") || undefined,
    });
    event.currentTarget.reset();
  }

  function handleTransfer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    transfer.mutate({
      sourceAccountId: accountId,
      destinationAccountId: String(form.get("destinationAccountId")),
      amount: Number(form.get("amount")),
      description: String(form.get("description") || "") || undefined,
    });
    event.currentTarget.reset();
  }

  const active = tab === "deposit" ? deposit : tab === "withdraw" ? withdraw : transfer;

  return (
    <div className="card">
      <div className="btn-row" style={{ marginBottom: "1rem" }}>
        <button className={`btn ${tab === "deposit" ? "" : "btn--secondary"}`} onClick={() => setTab("deposit")}>
          Deposit
        </button>
        <button className={`btn ${tab === "withdraw" ? "" : "btn--secondary"}`} onClick={() => setTab("withdraw")}>
          Withdraw
        </button>
        <button className={`btn ${tab === "transfer" ? "" : "btn--secondary"}`} onClick={() => setTab("transfer")}>
          Transfer
        </button>
      </div>

      <ErrorBanner error={active.error} />
      {active.isSuccess && <p style={{ color: "var(--color-success)" }}>Posted successfully.</p>}

      {tab === "deposit" && (
        <form className="form" onSubmit={handleDeposit}>
          <AmountField />
          <DescriptionField />
          <button className="btn" type="submit" disabled={deposit.isPending}>
            {deposit.isPending ? "Depositing…" : "Deposit"}
          </button>
        </form>
      )}
      {tab === "withdraw" && (
        <form className="form" onSubmit={handleWithdraw}>
          <AmountField />
          <DescriptionField />
          <button className="btn" type="submit" disabled={withdraw.isPending}>
            {withdraw.isPending ? "Withdrawing…" : "Withdraw"}
          </button>
        </form>
      )}
      {tab === "transfer" && (
        <form className="form" onSubmit={handleTransfer}>
          <div className="form-row">
            <label htmlFor="destinationAccountId">Destination account id</label>
            <input id="destinationAccountId" name="destinationAccountId" required />
          </div>
          <AmountField />
          <DescriptionField />
          <button className="btn" type="submit" disabled={transfer.isPending}>
            {transfer.isPending ? "Transferring…" : "Transfer"}
          </button>
        </form>
      )}
    </div>
  );
}

function AmountField() {
  return (
    <div className="form-row">
      <label htmlFor="amount">Amount</label>
      <input id="amount" name="amount" type="number" min={0.01} step="0.01" required />
    </div>
  );
}

function DescriptionField() {
  return (
    <div className="form-row">
      <label htmlFor="description">Description (optional)</label>
      <input id="description" name="description" maxLength={255} />
    </div>
  );
}
