import { Fragment, useState, type FormEvent } from "react";
import { useParams } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import {
  useAccount,
  useBalance,
  useCancelScheduledTransfer,
  useCloseAccount,
  useCreateScheduledTransfer,
  useDeposit,
  useFreezeAccount,
  useScheduledTransfers,
  useStatement,
  useTransfer,
  useUnfreezeAccount,
  useWithdraw,
  type AmountInput,
} from "../api/hooks";
import type { Account, ScheduleFrequency } from "../api/types";
import { ErrorBanner } from "../components/ErrorBanner";
import { StatusPill } from "../components/StatusPill";
import { formatAmount, formatCalendarDate, formatDateTime } from "../format";
import { isStaff, rolesFromAccessToken } from "../auth/roles";
import { canCancel, directionFor, scheduleAttention } from "../schedule";

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

      <ScheduledTransfersCard account={account} staff={staff} />

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
    deposit.mutate(readAmountAndDescription(new FormData(event.currentTarget)));
    event.currentTarget.reset();
  }

  function handleWithdraw(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    withdraw.mutate(readAmountAndDescription(new FormData(event.currentTarget)));
    event.currentTarget.reset();
  }

  function handleTransfer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    transfer.mutate({
      sourceAccountId: accountId,
      destinationAccountId: String(form.get("destinationAccountId")),
      ...readAmountAndDescription(form),
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

// Deposit, withdraw and transfer forms all carry the same amount/description pair, read the
// same way; transfer's handler spreads this in alongside its own two extra fields.
function readAmountAndDescription(form: FormData): Pick<AmountInput, "amount" | "description"> {
  return {
    amount: Number(form.get("amount")),
    description: String(form.get("description") || "") || undefined,
  };
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
/**
 * Standing instructions touching this account, in both directions. Visible to the owner as well
 * as to staff -- it is the customer's own money on a timer, and the backend's account-scoped rule
 * already lets them read it -- but only staff can create or cancel one.
 *
 * <p>The list leads with what the API makes visible and nothing else did: a mandate that has
 * failed but is still trying, and one that gave up. Both were previously discoverable only by
 * reading the JSON.
 */
export function ScheduledTransfersCard({ account, staff }: { account: Account; staff: boolean }) {
  const [page, setPage] = useState(0);
  const { data: schedules, error } = useScheduledTransfers(account.id, page);
  const cancel = useCancelScheduledTransfer();

  return (
    <div className="card">
      <h3>Standing instructions</h3>
      <ErrorBanner error={error || cancel.error} />

      <table>
        <thead>
          <tr>
            <th>Next</th>
            <th>Frequency</th>
            <th>Amount</th>
            <th>Description</th>
            <th>Status</th>
            {staff && <th />}
          </tr>
        </thead>
        <tbody>
          {schedules?.content.map((schedule) => {
            const attention = scheduleAttention(schedule);
            const outgoing = directionFor(schedule, account.id) === "out";
            return (
              <Fragment key={schedule.id}>
                <tr>
                  <td className="muted">
                    {schedule.nextRunOn ? formatCalendarDate(schedule.nextRunOn) : "—"}
                  </td>
                  <td>{schedule.frequency}</td>
                  {/* Signed from this account's side, the way a statement line is: the same
                      mandate is money leaving one account and arriving in the other. */}
                  <td className={`amount ${outgoing ? "amount--negative" : "amount--positive"}`}>
                    {formatAmount(outgoing ? -schedule.amount : schedule.amount, schedule.currency)}
                  </td>
                  <td className="muted">{schedule.description ?? "—"}</td>
                  <td><StatusPill status={schedule.status} /></td>
                  {staff && (
                    <td>
                      {canCancel(schedule) && (
                        <button
                          className="btn btn--secondary"
                          disabled={cancel.isPending}
                          onClick={() => cancel.mutate(schedule.id)}
                        >
                          Cancel
                        </button>
                      )}
                    </td>
                  )}
                </tr>
                {attention.level !== "none" && (
                  <tr>
                    <td colSpan={staff ? 6 : 5} className="muted">
                      {attention.message}
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
          {schedules && schedules.content.length === 0 && (
            <tr>
              <td colSpan={staff ? 6 : 5} className="muted">Nothing scheduled against this account.</td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="btn-row" style={{ marginTop: "1rem" }}>
        <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <button className="btn btn--secondary" disabled={schedules?.last} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </div>

      {staff && account.status !== "CLOSED" && <NewScheduleForm account={account} />}
    </div>
  );
}

function NewScheduleForm({ account }: { account: Account }) {
  const create = useCreateScheduledTransfer();

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const endsOn = String(form.get("endsOn") ?? "").trim();
    create.mutate({
      sourceAccountId: account.id,
      destinationAccountId: String(form.get("destinationAccountId")),
      amount: Number(form.get("amount")),
      currency: account.currency,
      description: String(form.get("description") ?? "").trim() || undefined,
      frequency: form.get("frequency") as ScheduleFrequency,
      startsOn: String(form.get("startsOn")),
      // Omitted rather than sent empty: the API reads a missing end date as "until cancelled",
      // and an empty string is not a date it will accept.
      endsOn: endsOn || undefined,
    });
    event.currentTarget.reset();
  }

  return (
    <>
      <h4 style={{ marginTop: "1.5rem" }}>New standing instruction</h4>
      <p className="muted">
        Pays out of this account on the schedule below. The first payment falls on the start date,
        which cannot be in the past.
      </p>
      <ErrorBanner error={create.error} />
      {create.isSuccess && <p style={{ color: "var(--color-success)" }}>Scheduled.</p>}
      <form className="form" onSubmit={handleSubmit}>
        <div className="form-row">
          <label htmlFor="destinationAccountId">Destination account id</label>
          <input id="destinationAccountId" name="destinationAccountId" required />
        </div>
        <div className="form-row">
          <label htmlFor="scheduleAmount">Amount</label>
          <input id="scheduleAmount" name="amount" type="number" step="0.01" min="0.01" required />
        </div>
        <div className="form-row">
          <label htmlFor="frequency">Frequency</label>
          <select id="frequency" name="frequency" defaultValue="MONTHLY">
            <option value="ONCE">Once</option>
            <option value="DAILY">Daily</option>
            <option value="WEEKLY">Weekly</option>
            <option value="MONTHLY">Monthly</option>
          </select>
        </div>
        <div className="form-row">
          <label htmlFor="startsOn">Starts on</label>
          <input id="startsOn" name="startsOn" type="date" required />
        </div>
        <div className="form-row">
          <label htmlFor="endsOn">Ends on (optional)</label>
          <input id="endsOn" name="endsOn" type="date" />
        </div>
        <div className="form-row">
          <label htmlFor="scheduleDescription">Description</label>
          <input id="scheduleDescription" name="description" maxLength={255} />
        </div>
        <button className="btn" type="submit" disabled={create.isPending}>
          {create.isPending ? "Scheduling…" : "Schedule"}
        </button>
      </form>
    </>
  );
}
