import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { useCategorisePreview } from "../api/insightsHooks";
import { useCustomerSearch, useTransactionSearch, type TransactionSearchFilters } from "../api/hooks";
import { ErrorBanner } from "../components/ErrorBanner";
import { StatusPill } from "../components/StatusPill";
import { formatAmount, formatDateTime } from "../format";
import type { TransactionType } from "../api/types";

type Tab = "transactions" | "customers" | "categoriser";

export function SearchPage() {
  const [tab, setTab] = useState<Tab>("transactions");

  return (
    <>
      <div className="page-header">
        <div>
          <h1>Search</h1>
          <p className="muted">Bank-wide, cross-account. Backed by the OpenSearch index, not the ledger directly.</p>
        </div>
      </div>

      <div className="btn-row" style={{ marginBottom: "1rem" }}>
        <button className={`btn ${tab === "transactions" ? "" : "btn--secondary"}`} onClick={() => setTab("transactions")}>
          Transactions
        </button>
        <button className={`btn ${tab === "customers" ? "" : "btn--secondary"}`} onClick={() => setTab("customers")}>
          Customers
        </button>
        <button className={`btn ${tab === "categoriser" ? "" : "btn--secondary"}`} onClick={() => setTab("categoriser")}>
          Try the categoriser
        </button>
      </div>

      {tab === "transactions" && <TransactionSearchTab />}
      {tab === "customers" && <CustomerSearchTab />}
      {tab === "categoriser" && <CategoriserTab />}
    </>
  );
}

function TransactionSearchTab() {
  const [filters, setFilters] = useState<TransactionSearchFilters>({});
  const [page, setPage] = useState(0);
  const { data, isLoading, error } = useTransactionSearch(filters, page);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPage(0);
    setFilters({
      q: String(form.get("q") || "") || undefined,
      type: (String(form.get("type") || "") || undefined) as TransactionType | undefined,
      minAmount: form.get("minAmount") ? Number(form.get("minAmount")) : undefined,
      maxAmount: form.get("maxAmount") ? Number(form.get("maxAmount")) : undefined,
      from: form.get("from") ? new Date(String(form.get("from"))).toISOString() : undefined,
      to: form.get("to") ? new Date(String(form.get("to"))).toISOString() : undefined,
    });
  }

  return (
    <>
      <div className="card">
        <form className="form" style={{ maxWidth: "none" }} onSubmit={handleSubmit}>
          <div className="stat-grid">
            <div className="form-row">
              <label htmlFor="q">Description contains</label>
              <input id="q" name="q" placeholder="electricity, rent, …" />
            </div>
            <div className="form-row">
              <label htmlFor="type">Type</label>
              <select id="type" name="type" defaultValue="">
                <option value="">Any</option>
                <option value="DEPOSIT">Deposit</option>
                <option value="WITHDRAWAL">Withdrawal</option>
                <option value="TRANSFER">Transfer</option>
              </select>
            </div>
            <div className="form-row">
              <label htmlFor="minAmount">Min amount</label>
              <input id="minAmount" name="minAmount" type="number" min={0} step="0.01" />
            </div>
            <div className="form-row">
              <label htmlFor="maxAmount">Max amount</label>
              <input id="maxAmount" name="maxAmount" type="number" min={0} step="0.01" />
            </div>
            <div className="form-row">
              <label htmlFor="from">Posted from</label>
              <input id="from" name="from" type="date" />
            </div>
            <div className="form-row">
              <label htmlFor="to">Posted to</label>
              <input id="to" name="to" type="date" />
            </div>
          </div>
          <div className="btn-row">
            <button className="btn" type="submit">Search</button>
          </div>
        </form>
      </div>

      <div className="card">
        <ErrorBanner error={error} />
        {isLoading && <p className="muted">Loading&hellip;</p>}
        {data && (
          <>
            <p className="muted">{data.totalHits} match{data.totalHits === 1 ? "" : "es"}</p>
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Type</th>
                  <th>Reference</th>
                  <th>Accounts</th>
                  <th>Description</th>
                  <th>Amount</th>
                </tr>
              </thead>
              <tbody>
                {data.hits.map((hit) => (
                  <tr key={hit.reference}>
                    <td className="muted">{formatDateTime(hit.postedAt)}</td>
                    <td>{hit.type}</td>
                    <td>
                      <Link to={`/transactions/${hit.reference}`}>{hit.reference}</Link>
                    </td>
                    <td className="muted">{hit.accountNumbers.join(", ")}</td>
                    <td className="muted">{hit.description ?? "—"}</td>
                    <td className="amount">{formatAmount(hit.amount, hit.currency)}</td>
                  </tr>
                ))}
                {data.hits.length === 0 && (
                  <tr>
                    <td colSpan={6} className="muted">No matches.</td>
                  </tr>
                )}
              </tbody>
            </table>
            <div className="btn-row" style={{ marginTop: "1rem" }}>
              <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </button>
              <button
                className="btn btn--secondary"
                disabled={(page + 1) * data.size >= data.totalHits}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </button>
            </div>
          </>
        )}
      </div>
    </>
  );
}

function CustomerSearchTab() {
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const { data, isLoading, error } = useCustomerSearch(query, page);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPage(0);
    setQuery(String(form.get("q") || ""));
  }

  return (
    <>
      <div className="card">
        <form className="form" style={{ maxWidth: "none", flexDirection: "row", alignItems: "flex-end" }} onSubmit={handleSubmit}>
          <div className="form-row" style={{ flex: 1 }}>
            <label htmlFor="customerQ">Name, email or customer number</label>
            <input id="customerQ" name="q" placeholder="asha, CUST0001, asha@example.com" />
          </div>
          <div className="btn-row">
            <button className="btn" type="submit">Search</button>
          </div>
        </form>
      </div>

      <div className="card">
        <ErrorBanner error={error} />
        {isLoading && <p className="muted">Loading&hellip;</p>}
        {data && (
          <>
            <p className="muted">{data.totalHits} match{data.totalHits === 1 ? "" : "es"}</p>
            <table>
              <thead>
                <tr>
                  <th>Number</th>
                  <th>Name</th>
                  <th>Email</th>
                  <th>KYC</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {data.hits.map((hit) => (
                  <tr key={hit.id}>
                    <td>
                      <Link to={`/customers/${hit.id}`}>{hit.customerNumber}</Link>
                    </td>
                    <td>{hit.firstName} {hit.lastName}</td>
                    <td>{hit.email}</td>
                    <td><StatusPill status={hit.kycStatus} /></td>
                    <td><StatusPill status={hit.status} /></td>
                  </tr>
                ))}
                {data.hits.length === 0 && (
                  <tr>
                    <td colSpan={5} className="muted">No matches.</td>
                  </tr>
                )}
              </tbody>
            </table>
            <div className="btn-row" style={{ marginTop: "1rem" }}>
              <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </button>
              <button
                className="btn btn--secondary"
                disabled={(page + 1) * data.size >= data.totalHits}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </button>
            </div>
          </>
        )}
      </div>
    </>
  );
}

function CategoriserTab() {
  const categorise = useCategorisePreview();

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const description = String(new FormData(event.currentTarget).get("description") || "");
    if (description) {
      categorise.mutate(description);
    }
  }

  return (
    <div className="card">
      <h3>Try the categoriser</h3>
      <p className="muted">
        Runs the spending-insights model over arbitrary text, without touching the ledger --
        useful for seeing why a description lands in a given category.
      </p>
      <ErrorBanner error={categorise.error} />
      <form className="form" onSubmit={handleSubmit}>
        <div className="form-row">
          <label htmlFor="description">Description</label>
          <input id="description" name="description" required maxLength={255} placeholder="zomato dinner" />
        </div>
        <div className="btn-row">
          <button className="btn" type="submit" disabled={categorise.isPending}>
            {categorise.isPending ? "Categorising…" : "Categorise"}
          </button>
        </div>
      </form>
      {categorise.data && (
        <div className="stat-grid" style={{ marginTop: "1rem" }}>
          <div className="stat">
            <div className="stat__label">Category</div>
            <div className="stat__value">{categorise.data.category}</div>
          </div>
          <div className="stat">
            <div className="stat__label">Confidence</div>
            <div className="stat__value">{(categorise.data.confidence * 100).toFixed(1)}%</div>
          </div>
        </div>
      )}
    </div>
  );
}
