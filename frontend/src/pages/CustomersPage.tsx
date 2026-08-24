import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { useCreateCustomer, useCustomers } from "../api/hooks";
import { ErrorBanner, fieldErrors } from "../components/ErrorBanner";
import { StatusPill } from "../components/StatusPill";
import { formatDate } from "../format";

export function CustomersPage() {
  const [page, setPage] = useState(0);
  const [showForm, setShowForm] = useState(false);
  const { data, isLoading, error } = useCustomers(page);

  return (
    <>
      <div className="page-header">
        <div>
          <h1>Customers</h1>
          <p className="muted">Onboarding, KYC and account ownership.</p>
        </div>
        <button className="btn" onClick={() => setShowForm((v) => !v)}>
          {showForm ? "Cancel" : "New customer"}
        </button>
      </div>

      {showForm && <NewCustomerForm onDone={() => setShowForm(false)} />}

      <div className="card">
        <ErrorBanner error={error} />
        {isLoading && <p className="muted">Loading&hellip;</p>}
        {data && (
          <>
            <table>
              <thead>
                <tr>
                  <th>Number</th>
                  <th>Name</th>
                  <th>Email</th>
                  <th>KYC</th>
                  <th>Status</th>
                  <th>Identity</th>
                  <th>Onboarded</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((customer) => (
                  <tr key={customer.id}>
                    <td>
                      <Link to={`/customers/${customer.id}`}>{customer.customerNumber}</Link>
                    </td>
                    <td>
                      {customer.firstName} {customer.lastName}
                    </td>
                    <td>{customer.email}</td>
                    <td><StatusPill status={customer.kycStatus} /></td>
                    <td><StatusPill status={customer.status} /></td>
                    <td>{customer.identityLinked ? "Linked" : <span className="muted">Not linked</span>}</td>
                    <td className="muted">{formatDate(customer.createdAt)}</td>
                  </tr>
                ))}
                {data.content.length === 0 && (
                  <tr>
                    <td colSpan={7} className="muted">No customers yet.</td>
                  </tr>
                )}
              </tbody>
            </table>
            <div className="btn-row" style={{ marginTop: "1rem" }}>
              <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </button>
              <button className="btn btn--secondary" disabled={data.last} onClick={() => setPage((p) => p + 1)}>
                Next
              </button>
            </div>
          </>
        )}
      </div>
    </>
  );
}

function NewCustomerForm({ onDone }: { onDone: () => void }) {
  const createCustomer = useCreateCustomer();
  const errors = fieldErrors(createCustomer.error);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    createCustomer.mutate(
      {
        firstName: String(form.get("firstName")),
        lastName: String(form.get("lastName")),
        email: String(form.get("email")),
        phone: String(form.get("phone") || "") || undefined,
        dateOfBirth: String(form.get("dateOfBirth")),
      },
      { onSuccess: onDone },
    );
  }

  return (
    <div className="card">
      <h3>Onboard a customer</h3>
      <ErrorBanner error={createCustomer.error} />
      <form className="form" onSubmit={handleSubmit}>
        <div className="form-row">
          <label htmlFor="firstName">First name</label>
          <input id="firstName" name="firstName" required maxLength={60} />
        </div>
        <div className="form-row">
          <label htmlFor="lastName">Last name</label>
          <input id="lastName" name="lastName" required maxLength={60} />
        </div>
        <div className="form-row">
          <label htmlFor="email">Email</label>
          <input id="email" name="email" type="email" required maxLength={160} />
          {errors?.email && <span className="field-error">{errors.email}</span>}
        </div>
        <div className="form-row">
          <label htmlFor="phone">Phone (optional)</label>
          <input id="phone" name="phone" placeholder="+919876543210" />
        </div>
        <div className="form-row">
          <label htmlFor="dateOfBirth">Date of birth</label>
          <input id="dateOfBirth" name="dateOfBirth" type="date" required />
          {errors?.dateOfBirth && <span className="field-error">{errors.dateOfBirth}</span>}
        </div>
        <div className="btn-row">
          <button className="btn" type="submit" disabled={createCustomer.isPending}>
            {createCustomer.isPending ? "Creating…" : "Create customer"}
          </button>
        </div>
      </form>
    </div>
  );
}
