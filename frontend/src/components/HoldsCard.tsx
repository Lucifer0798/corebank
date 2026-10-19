import { Fragment, useState } from "react";
import { useCaptureHold, useHolds, useReleaseHold } from "../api/hooks";
import type { Account, Hold } from "../api/types";
import { formatAmount, formatDateTime } from "../format";
import { ErrorBanner } from "./ErrorBanner";
import { StatusPill } from "./StatusPill";

/** Only an outstanding hold can be captured or released; the backend refuses the rest. */
export function canSettle(hold: Hold): boolean {
  return hold.status === "ACTIVE";
}

/**
 * What a blank or typed capture amount means. Blank is the documented way to capture in full; the
 * backend applies its own rules to anything else, including more than was held.
 */
export function captureAmount(typed: string): number | undefined | null {
  const trimmed = typed.trim();
  if (trimmed === "") {
    return undefined;
  }
  const value = Number(trimmed);
  return Number.isFinite(value) && value > 0 ? value : null;
}

function who(actor: Hold["placedBy"]): string | null {
  return actor ? (actor.name ?? actor.subject) : null;
}

/**
 * The holds behind the "On hold" figure. That figure alone told a customer that money was reserved
 * but not by whom or until when -- the very complaint the page's own comment names. Everyone who can
 * see the account sees this list; staff also see who placed and settled each hold, and can capture
 * or release an outstanding one.
 */
export function HoldsCard({ account, staff }: { account: Account; staff: boolean }) {
  const [page, setPage] = useState(0);
  const { data: holds, error } = useHolds(account.id, page);
  const capture = useCaptureHold();
  const release = useReleaseHold();

  return (
    <div className="card">
      <h3>Holds</h3>
      <ErrorBanner error={error || capture.error || release.error} />

      <table>
        <thead>
          <tr>
            <th>Placed</th>
            <th>Description</th>
            <th>Amount</th>
            <th>Expires</th>
            <th>Status</th>
            {staff && <th />}
          </tr>
        </thead>
        <tbody>
          {holds?.content.map((hold) => (
            <Fragment key={hold.id}>
              <tr>
                <td className="muted">{formatDateTime(hold.placedAt)}</td>
                <td>
                  {hold.description ?? "—"}
                  {staff && who(hold.placedBy) && <div className="muted">Placed by {who(hold.placedBy)}</div>}
                  {staff && who(hold.settledBy) && <div className="muted">Settled by {who(hold.settledBy)}</div>}
                </td>
                <td className="amount">{formatAmount(hold.amount, hold.currency)}</td>
                <td className="muted">{canSettle(hold) ? formatDateTime(hold.expiresAt) : "—"}</td>
                <td><StatusPill status={hold.status} /></td>
                {staff && (
                  <td>
                    {canSettle(hold) && (
                      <CaptureControls
                        hold={hold}
                        busy={capture.isPending || release.isPending}
                        onCapture={(amount) => capture.mutate({ reference: hold.reference, amount })}
                        onRelease={() => release.mutate(hold.reference)}
                      />
                    )}
                  </td>
                )}
              </tr>
            </Fragment>
          ))}
          {holds && holds.content.length === 0 && (
            <tr>
              <td colSpan={staff ? 6 : 5} className="muted">No holds on this account.</td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="btn-row" style={{ marginTop: "1rem" }}>
        <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <button className="btn btn--secondary" disabled={holds?.last ?? true} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}

function CaptureControls({ hold, busy, onCapture, onRelease }: {
  hold: Hold;
  busy: boolean;
  onCapture: (amount: number | undefined) => void;
  onRelease: () => void;
}) {
  const [typed, setTyped] = useState("");
  const amount = captureAmount(typed);

  return (
    <div className="btn-row">
      <input
        aria-label={`Capture amount for ${hold.reference}`}
        placeholder="Full amount"
        inputMode="decimal"
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        style={{ width: "8rem" }}
      />
      <button className="btn" disabled={busy || amount === null} onClick={() => onCapture(amount ?? undefined)}>
        Capture
      </button>
      <button className="btn btn--secondary" disabled={busy} onClick={onRelease}>
        Release
      </button>
    </div>
  );
}
