import { useState, type FormEvent } from "react";
import { useAuth } from "react-oidc-context";
import { useReplay, type ReplayKind } from "../api/hooks";
import { isAdmin, rolesFromAccessToken } from "../auth/roles";
import { ErrorBanner } from "../components/ErrorBanner";
import { FxBookCard } from "../components/FxBookCard";
import { formatDateTime } from "../format";
import { preset, replayWindow } from "../replayWindow";

const DESCRIPTIONS: Record<ReplayKind, string> = {
  transactions:
    "Re-sends every transaction posted in the window, as it now stands. Use it to rebuild search and "
    + "spending insights after a gap, or to bring older records up to date after an upgrade that "
    + "added a field (reversal status, leg currencies).",
  customers:
    "Re-sends every customer whose record changed in the window, so customer search reflects the "
    + "current name, email and status.",
};

/** Admin tools. Today: replaying events through the outbox, which used to need a hand-built API call. */
export function AdminPage() {
  const auth = useAuth();
  const admin = isAdmin(rolesFromAccessToken(auth.user?.access_token));

  if (!admin) {
    return (
      <div className="card">
        <h2>Admins only</h2>
        <p className="muted">These tools need the ADMIN role.</p>
      </div>
    );
  }

  return (
    <>
      <div className="page-header">
        <div>
          <h1>Admin</h1>
          <p className="muted">
            Replaying is safe to run more than once over the same window: every consumer upserts by
            key rather than appending.
          </p>
        </div>
      </div>
      <FxBookCard />
      <ReplayCard kind="transactions" />
      <ReplayCard kind="customers" />
    </>
  );
}

export function ReplayCard({ kind }: { kind: ReplayKind }) {
  const replay = useReplay(kind);
  const [since, setSince] = useState("");
  const [until, setUntil] = useState("");
  const [invalid, setInvalid] = useState<string | null>(null);
  const [ran, setRan] = useState<{ since: string; until: string } | null>(null);
  const title = kind === "transactions" ? "Replay transactions" : "Replay customers";

  function apply(window: { since: string; until: string }) {
    setSince(window.since);
    setUntil(window.until);
    setInvalid(null);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const window = replayWindow(since, until);
    if ("error" in window) {
      setInvalid(window.error);
      return;
    }
    setInvalid(null);
    replay.mutate(window, { onSuccess: () => setRan(window) });
  }

  return (
    <div className="card">
      <h3>{title}</h3>
      <p className="muted">{DESCRIPTIONS[kind]}</p>
      <ErrorBanner error={replay.error} />
      {invalid && <p className="field-error">{invalid}</p>}
      {replay.isSuccess && ran && (
        <p style={{ color: "var(--color-success)" }}>
          Re-sent {replay.data.eventsEnqueued} {kind === "transactions" ? "transaction" : "customer"}{" "}
          event{replay.data.eventsEnqueued === 1 ? "" : "s"} for {formatDateTime(ran.since)} to{" "}
          {formatDateTime(ran.until)}. Search and insights pick them up within seconds.
        </p>
      )}

      <div className="btn-row" style={{ marginBottom: "0.75rem" }}>
        <button type="button" className="btn btn--secondary" onClick={() => apply(preset("day"))}>Last 24 hours</button>
        <button type="button" className="btn btn--secondary" onClick={() => apply(preset("month"))}>Last 30 days</button>
        <button type="button" className="btn btn--secondary" onClick={() => apply(preset("everything"))}>Everything</button>
      </div>

      <form className="form" onSubmit={submit}>
        <div className="form-row">
          <label htmlFor={`${kind}-since`}>From (your local time)</label>
          <input id={`${kind}-since`} type="datetime-local" value={since} onChange={(e) => setSince(e.target.value)} />
        </div>
        <div className="form-row">
          <label htmlFor={`${kind}-until`}>Up to, not including</label>
          <input id={`${kind}-until`} type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} />
        </div>
        <button className="btn" type="submit" disabled={replay.isPending}>
          {replay.isPending ? "Replaying…" : title}
        </button>
      </form>
    </div>
  );
}
