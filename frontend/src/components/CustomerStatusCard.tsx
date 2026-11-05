import { useState, type FormEvent } from "react";
import { useChangeCustomerStatus, useCustomerStatusChanges } from "../api/hooks";
import type { CustomerStatus } from "../api/types";
import { customerReasonRequired, customerStatusActionsFor, type CustomerStatusAction } from "../customerStatus";
import { formatDateTime } from "../format";
import { ErrorBanner } from "./ErrorBanner";
import { StatusPill } from "./StatusPill";

const LABELS: Record<CustomerStatusAction, string> = {
  suspend: "Suspend",
  reinstate: "Reinstate",
  close: "Close customer",
};

/**
 * Suspending, reinstating and closing a customer, and the history of each -- who, when and why. The
 * history is for any staff member; only an admin can make a change. Staff only: the reason for a
 * suspension can be a fraud report or a legal order.
 */
export function CustomerStatusCard({
  customerId,
  status,
  admin,
}: {
  customerId: string;
  status: CustomerStatus;
  admin: boolean;
}) {
  const [page, setPage] = useState(0);
  const { data: changes, error } = useCustomerStatusChanges(customerId, page);
  const actions = customerStatusActionsFor(status, admin);

  return (
    <div className="card">
      <h3>Customer status</h3>
      <ErrorBanner error={error} />
      {status === "SUSPENDED" && (
        <p className="muted">Money cannot leave this customer's accounts, and they cannot open new ones. Payments in still arrive.</p>
      )}
      {/* Keyed on the status so a change resets the form to the actions that make sense now. */}
      {actions.length > 0 && <StatusChangeForm key={status} customerId={customerId} actions={actions} />}

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
              <td colSpan={4} className="muted">No changes recorded.</td>
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

function StatusChangeForm({ customerId, actions }: { customerId: string; actions: CustomerStatusAction[] }) {
  const change = useChangeCustomerStatus(customerId);
  const [action, setAction] = useState<CustomerStatusAction>(actions[0]);
  const [reason, setReason] = useState("");

  const needsReason = customerReasonRequired(action);
  const canSubmit = !change.isPending && (!needsReason || reason.trim().length > 0);

  function submit(event: FormEvent) {
    event.preventDefault();
    change.mutate({ action, reason: reason.trim() || undefined });
  }

  return (
    <form className="form" onSubmit={submit} style={{ marginBottom: "1rem" }}>
      <ErrorBanner error={change.error} />
      <div className="form-row">
        <label htmlFor="customerStatusAction">Change</label>
        <select
          id="customerStatusAction"
          value={action}
          onChange={(e) => setAction(e.target.value as CustomerStatusAction)}
        >
          {actions.map((option) => (
            <option key={option} value={option}>{LABELS[option]}</option>
          ))}
        </select>
      </div>
      <div className="form-row">
        <label htmlFor="customerStatusReason">Reason{needsReason ? " (required)" : " (optional)"}</label>
        <textarea id="customerStatusReason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
      </div>
      <button className={action === "reinstate" ? "btn" : "btn btn--danger"} type="submit" disabled={!canSubmit}>
        {LABELS[action]}
      </button>
    </form>
  );
}
