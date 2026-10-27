import { Link } from "react-router-dom";
import { useFxPosition, useRevalue } from "../api/hooks";
import { formatAmount, formatDateTime } from "../format";
import { describeUnrecognised, unrecognised } from "../fxBook";
import { ErrorBanner } from "./ErrorBanner";

/**
 * The bank's own FX exposure, and the close that marks it to market. Both used to be API-only.
 *
 * <p>A close posts the change in the mark since the last one -- the delta, not the mark -- so
 * running it twice in a row posts nothing the second time, and the card says so rather than
 * treating "nothing to do" as a failure.
 */
export function FxBookCard() {
  const { data: report, error } = useFxPosition();
  const revalue = useRevalue();

  const gap = report ? unrecognised(report) : 0;
  const kind = describeUnrecognised(gap);

  return (
    <div className="card">
      <h3>FX book</h3>
      <p className="muted">
        The bank's own position in each currency, valued at mid rates into the reporting currency.
      </p>
      <ErrorBanner error={error || revalue.error} />

      {report && (
        <>
          <table>
            <thead>
              <tr>
                <th>Currency</th>
                <th>Position</th>
                <th>Rate</th>
                <th>Value ({report.reportingCurrency})</th>
              </tr>
            </thead>
            <tbody>
              {report.positions.map((line) => (
                <tr key={line.currency}>
                  <td>{line.currency}</td>
                  <td className="amount">
                    {formatAmount(line.balance, line.currency)}
                    {line.balance < 0 && <span className="muted"> (short)</span>}
                  </td>
                  <td className="muted">{line.rateToReporting}</td>
                  <td className="amount">{formatAmount(line.valueInReportingCurrency, report.reportingCurrency)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="stat-grid" style={{ marginTop: "1rem" }}>
            <div className="stat">
              <div className="stat__label">Mark to market</div>
              <div className="stat__value">{formatAmount(report.markToMarket, report.reportingCurrency)}</div>
            </div>
            <div className="stat">
              <div className="stat__label">Carried at</div>
              <div className="stat__value">{formatAmount(report.markCarried, report.reportingCurrency)}</div>
            </div>
            <div className="stat">
              <div className="stat__label">A close would recognise</div>
              <div className="stat__value">
                {kind === "none"
                  ? "Nothing"
                  : `A ${kind} of ${formatAmount(Math.abs(gap), report.reportingCurrency)}`}
              </div>
            </div>
          </div>
          <p className="muted">As of {formatDateTime(report.asOf)}.</p>
        </>
      )}

      {revalue.isSuccess && revalue.data && (
        revalue.data.reference ? (
          <p style={{ color: "var(--color-success)" }}>
            Posted <Link to={`/transactions/${revalue.data.reference}`}>{revalue.data.reference}</Link>; the book is
            now carried at {formatAmount(revalue.data.markCarried, report?.reportingCurrency ?? "INR")}.
          </p>
        ) : (
          <p className="muted">Nothing to do: the mark has not moved since the last close.</p>
        )
      )}

      <div className="btn-row" style={{ marginTop: "0.75rem" }}>
        <button className="btn" disabled={revalue.isPending} onClick={() => revalue.mutate()}>
          {revalue.isPending ? "Revaluing…" : "Revalue"}
        </button>
      </div>
    </div>
  );
}
