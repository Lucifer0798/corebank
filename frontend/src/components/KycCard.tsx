import { useState, type FormEvent } from "react";
import { useKycDecisions, useUpdateKyc } from "../api/hooks";
import type { KycDecision, KycStatus } from "../api/types";
import { formatDateTime } from "../format";
import { ErrorBanner } from "./ErrorBanner";
import { StatusPill } from "./StatusPill";

const STATUSES: KycStatus[] = ["VERIFIED", "PENDING", "REJECTED"];

/**
 * Anything but VERIFIED stops money leaving the customer's accounts, so the backend wants to be told
 * why -- and refuses with KYC_REASON_REQUIRED otherwise. Mirrored here so the form says so before
 * the round trip rather than after it.
 */
export function kycReasonRequired(target: KycStatus): boolean {
  return target !== "VERIFIED";
}

/**
 * KYC decisions on one customer: the history for any staff member, and for an admin a form to
 * record a new one.
 *
 * <p>The form is offered in every state, not only PENDING. Before, the only decisions the page
 * could make were the first one; a verified customer could not be sent back for review or rejected
 * from here at all, though since a lapsed KYC blocks outgoing money that is the decision most worth
 * being able to make -- and to explain.
 */
export function KycCard({
  customerId,
  current,
  admin,
}: {
  customerId: string;
  current: KycStatus;
  admin: boolean;
}) {
  const [page, setPage] = useState(0);
  const { data: decisions, error } = useKycDecisions(customerId, page);

  return (
    <div className="card">
      <h3>KYC decisions</h3>
      <ErrorBanner error={error} />
      {/* Keyed on the status, so a successful decision resets the form to choices that make sense
          for the new one rather than leaving the old target -- now the current status -- selected. */}
      {admin && <KycDecisionForm key={current} customerId={customerId} current={current} />}

      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Decision</th>
            <th>By</th>
            <th>Reason</th>
          </tr>
        </thead>
        <tbody>
          {decisions?.content.map((decision) => (
            <tr key={decision.id}>
              <td>{formatDateTime(decision.decidedAt)}</td>
              <td>
                <StatusPill status={decision.fromStatus} /> &rarr; <StatusPill status={decision.toStatus} />
              </td>
              <td>{deciderOf(decision)}</td>
              <td className={decision.reason ? undefined : "muted"}>{decision.reason ?? "—"}</td>
            </tr>
          ))}
          {decisions && decisions.content.length === 0 && (
            <tr>
              <td colSpan={4} className="muted">
                No decisions recorded. History starts from when it was introduced; earlier decisions were
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
        <button className="btn btn--secondary" disabled={decisions?.last ?? true} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}

function KycDecisionForm({ customerId, current }: { customerId: string; current: KycStatus }) {
  const updateKyc = useUpdateKyc(customerId);
  const choices = STATUSES.filter((status) => status !== current);
  const [target, setTarget] = useState<KycStatus>(choices[0]);
  const [reason, setReason] = useState("");

  const needsReason = kycReasonRequired(target);
  const canSubmit = !updateKyc.isPending && (!needsReason || reason.trim().length > 0);

  function submit(event: FormEvent) {
    event.preventDefault();
    updateKyc.mutate(
      { kycStatus: target, reason: reason.trim() || undefined },
      { onSuccess: () => setReason("") },
    );
  }

  return (
    <form className="form" onSubmit={submit} style={{ marginBottom: "1rem" }}>
      <ErrorBanner error={updateKyc.error} />
      <div className="form-row">
        <label htmlFor="kycTarget">New status</label>
        <select id="kycTarget" value={target} onChange={(e) => setTarget(e.target.value as KycStatus)}>
          {choices.map((status) => (
            <option key={status} value={status}>{status}</option>
          ))}
        </select>
      </div>
      <div className="form-row">
        <label htmlFor="kycReason">Reason{needsReason ? " (required)" : " (optional)"}</label>
        <textarea id="kycReason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
      </div>
      {needsReason && (
        <p className="muted">Money will stop leaving this customer's accounts. Payments in still arrive.</p>
      )}
      <button className={needsReason ? "btn btn--danger" : "btn"} type="submit" disabled={!canSubmit}>
        Record decision
      </button>
    </form>
  );
}

/** The username when there is one; a system process by its name; otherwise the raw subject. */
function deciderOf(decision: KycDecision): string {
  return decision.decidedByName ?? decision.decidedBySubject;
}
