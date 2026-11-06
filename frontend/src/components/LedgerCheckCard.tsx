import { useLedgerCheck } from "../api/hooks";
import { formatDateTime } from "../format";
import { ErrorBanner } from "./ErrorBanner";

/**
 * Whether the books still add up: the same check that runs every night, on demand. It reports what
 * disagrees and repairs nothing. It only runs when asked, since it totals whole tables.
 */
export function LedgerCheckCard() {
  const { data: report, error, isFetching, refetch } = useLedgerCheck();
  const failing = report?.checks.filter((check) => check.discrepancies.length > 0) ?? [];

  return (
    <div className="card">
      <h3>Ledger check</h3>
      <p className="muted">
        Compares every running total with the ledger it summarises. Read-only: it reports what disagrees
        and repairs nothing. It also runs every night.
      </p>
      <ErrorBanner error={error} />

      {report && report.clean && (
        <p>
          <strong>All clear.</strong> {report.checks.length} checks passed at {formatDateTime(report.checkedAt)}.
        </p>
      )}

      {report && !report.clean && (
        <>
          <p>
            <strong>
              {failing.length} of {report.checks.length} checks failed
            </strong>{" "}
            at {formatDateTime(report.checkedAt)}.
          </p>
          {failing.map((check) => (
            <div key={check.check} style={{ marginBottom: "1rem" }}>
              <h4>{check.rule}</h4>
              <table>
                <thead>
                  <tr>
                    <th>Where</th>
                    <th>What disagrees</th>
                  </tr>
                </thead>
                <tbody>
                  {check.discrepancies.map((found) => (
                    <tr key={`${found.subject} ${found.detail}`}>
                      <td>{found.subject}</td>
                      <td>{found.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {check.more && <p className="muted">There are more than the {check.discrepancies.length} listed.</p>}
            </div>
          ))}
        </>
      )}

      <div className="btn-row" style={{ marginTop: "0.75rem" }}>
        <button className="btn" disabled={isFetching} onClick={() => refetch()}>
          {isFetching ? "Checking…" : "Run the check"}
        </button>
      </div>
    </div>
  );
}
