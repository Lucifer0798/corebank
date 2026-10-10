import { useState, type FormEvent } from "react";
import { useAccountStatusChanges, useChangeAccountStatus, type AccountStatusAction } from "../api/hooks";
import type { Account, AccountStatus } from "../api/types";
import { formatDateTime } from "../format";
import { ErrorBanner } from "./ErrorBanner";
import { StatusPill } from "./StatusPill";

const LABELS: Record<AccountStatusAction, string> = {
  freeze: "Freeze",
  unfreeze: "Unfreeze",
  close: "Close account",
};

/**
 * What can be done to an account in this status, by this member of staff. Closing is admin-only on
 * the backend, so a teller is not offered a button that can only produce a 403.
 */
export function statusActionsFor(status: AccountStatus, admin: boolean): AccountStatusAction[] {
  if (status === "CLOSED") {
    return [];
  }
  const actions: AccountStatusAction[] = [status === "ACTIVE" ? "freeze" : "unfreeze"];
  if (admin) {
    actions.push("close");
  }
  return actions;
}

/** Freezing and closing need a reason; the backend refuses them without one. Unfreezing does not. */
export function statusReasonRequired(action: AccountStatusAction): boolean {
  return action !== "unfreeze";
}

/**
 * Freezing, unfreezing and closing an account, and the history of each -- who, when and why. Staff
 * only: the reason for a freeze can be a fraud report or a legal order.
 */
export function AccountStatusCard({ account, admin }: { account: Account; admin: boolean }) {
  const [page, setPage] = useState(0);
  const { data: changes, error } = useAccountStatusChanges(account.id, page);
  const actions = statusActionsFor(account.status, admin);

  return (
    <div className="card">
      <h3>Account status</h3>
      <ErrorBanner error={error} />
      {/* Keyed on the status so a change resets the form to the actions that make sense now. */}
      {actions.length > 0 && <StatusChangeForm key={account.status} accountId={account.id} actions={actions} />}

      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Change</th>
            <th>By</th>
            <th>Reason</th>
          </tr>
        </thead>
        <tbody>
          {changes?.content.map((change) => (
            <tr key={change.id}>
              <td>{formatDateTime(change.changedAt)}</td>
              <td>
                <StatusPill status={change.fromStatus} /> &rarr; <StatusPill status={change.toStatus} />
              </td>
              <td>{change.changedByName ?? change.changedBySubject}</td>
              <td className={change.reason ? undefined : "muted"}>{change.reason ?? "—"}</td>
            </tr>
          ))}
          {changes && changes.content.length === 0 && (
            <tr>
              <td colSpan={4} className="muted">
                No changes recorded. History starts from when it was introduced; earlier changes were
                never kept.
              </td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="btn-row" style={{ marginTop: "1rem" }}>
        <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <button className="btn btn--secondary" disabled={changes?.last ?? true} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}

function StatusChangeForm({ accountId, actions }: { accountId: string; actions: AccountStatusAction[] }) {
  const change = useChangeAccountStatus();
  const [action, setAction] = useState<AccountStatusAction>(actions[0]);
  const [reason, setReason] = useState("");

  const needsReason = statusReasonRequired(action);
  const canSubmit = !change.isPending && (!needsReason || reason.trim().length > 0);

  function submit(event: FormEvent) {
    event.preventDefault();
    change.mutate({ accountId, action, reason: reason.trim() || undefined });
  }

  return (
    <form className="form" onSubmit={submit} style={{ marginBottom: "1rem" }}>
      <ErrorBanner error={change.error} />
      <div className="form-row">
        <label htmlFor="statusAction">Change</label>
        <select id="statusAction" value={action} onChange={(e) => setAction(e.target.value as AccountStatusAction)}>
          {actions.map((option) => (
            <option key={option} value={option}>{LABELS[option]}</option>
          ))}
        </select>
      </div>
      <div className="form-row">
        <label htmlFor="statusReason">Reason{needsReason ? " (required)" : " (optional)"}</label>
        <textarea id="statusReason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
      </div>
      <button className={action === "unfreeze" ? "btn" : "btn btn--danger"} type="submit" disabled={!canSubmit}>
        {LABELS[action]}
      </button>
    </form>
  );
}
