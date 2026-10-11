import { useState, type FormEvent } from "react";
import { useIdentityLinks, useRelinkIdentity } from "../api/hooks";
import { formatDateTime } from "../format";
import { ErrorBanner } from "./ErrorBanner";

/**
 * Which logins have been linked to this customer, by whom and why -- and, for an admin, replacing
 * the current one.
 *
 * <p>A relink takes access to the customer's accounts from one login and gives it to another, so
 * the form says so and wants a reason. A first link stays on LinkIdentityCard, available to any
 * member of staff while the customer has none.
 */
export function IdentityCard({ customerId, linked, admin }: { customerId: string; linked: boolean; admin: boolean }) {
  const [page, setPage] = useState(0);
  const { data: links, error } = useIdentityLinks(customerId, page);

  return (
    <div className="card">
      <h3>Sign-in identity</h3>
      <ErrorBanner error={error} />
      {admin && linked && <RelinkForm customerId={customerId} />}

      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Linked</th>
            <th>Replaced</th>
            <th>By</th>
            <th>Reason</th>
          </tr>
        </thead>
        <tbody>
          {links?.content.map((link) => (
            <tr key={link.id}>
              <td>{formatDateTime(link.linkedAt)}</td>
              <td><code>{link.newSubject}</code></td>
              <td className={link.previousSubject ? undefined : "muted"}>
                {link.previousSubject ? <code>{link.previousSubject}</code> : "— (first link)"}
              </td>
              <td>{link.linkedByName ?? link.linkedBySubject}</td>
              <td className={link.reason ? undefined : "muted"}>{link.reason ?? "—"}</td>
            </tr>
          ))}
          {links && links.content.length === 0 && (
            <tr>
              <td colSpan={5} className="muted">
                No links recorded. History starts from when it was introduced; earlier links were never kept.
              </td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="btn-row" style={{ marginTop: "1rem" }}>
        <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <button className="btn btn--secondary" disabled={links?.last ?? true} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}

function RelinkForm({ customerId }: { customerId: string }) {
  const relink = useRelinkIdentity(customerId);
  const [subject, setSubject] = useState("");
  const [reason, setReason] = useState("");

  const canSubmit = !relink.isPending && subject.trim().length > 0 && reason.trim().length > 0;

  function submit(event: FormEvent) {
    event.preventDefault();
    relink.mutate(
      { keycloakSubject: subject.trim(), reason: reason.trim() },
      { onSuccess: () => { setSubject(""); setReason(""); } },
    );
  }

  return (
    <form className="form" onSubmit={submit} style={{ marginBottom: "1rem" }}>
      <ErrorBanner error={relink.error} />
      <p className="muted">
        Replacing the linked identity takes access to this customer's accounts away from the current
        login and gives it to the new one.
      </p>
      <div className="form-row">
        <label htmlFor="relinkSubject">New Keycloak subject (ID)</label>
        <input id="relinkSubject" value={subject} maxLength={64} onChange={(e) => setSubject(e.target.value)} />
      </div>
      <div className="form-row">
        <label htmlFor="relinkReason">Reason (required)</label>
        <textarea id="relinkReason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
      </div>
      <button className="btn btn--danger" type="submit" disabled={!canSubmit}>Replace identity</button>
    </form>
  );
}
