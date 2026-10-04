import { useState } from "react";
import { Link } from "react-router-dom";
import { useNotifications } from "../api/hooks";
import { formatDateTime } from "../format";
import { ErrorBanner } from "./ErrorBanner";
import { StatusPill } from "./StatusPill";

/**
 * What a customer has been told about money moving, newest first.
 *
 * <p>Without {@code customerId} this is the signed-in customer's own list; with one, it is staff
 * looking at what that customer was told -- the first thing to check when someone says they never
 * heard about a payment. Only the staff view links the reference: a customer opening a transaction
 * gets the backend's 403, so a link there would be a dead end.
 *
 * <p>The message is shown exactly as the backend wrote it. It is what the customer read, and
 * re-deriving it here from the amount and direction would make this list able to disagree with it.
 */
export function NotificationsCard({ customerId }: { customerId?: string }) {
  const [page, setPage] = useState(0);
  const { data: notifications, error } = useNotifications(customerId, page);
  const staffView = Boolean(customerId);

  return (
    <div className="card">
      <h3>Notifications</h3>
      <ErrorBanner error={error} />

      <table>
        <thead>
          <tr>
            <th>When</th>
            <th>Message</th>
            <th>Reference</th>
          </tr>
        </thead>
        <tbody>
          {notifications?.content.map((notification) => (
            <tr key={notification.id}>
              <td>{formatDateTime(notification.createdAt)}</td>
              <td>
                {notification.message}
                {notification.transactionStatus === "REVERSED" && (
                  <>
                    {" "}
                    <StatusPill status="REVERSED" />
                  </>
                )}
              </td>
              <td>
                {staffView ? (
                  <Link to={`/transactions/${notification.transactionReference}`}>
                    {notification.transactionReference}
                  </Link>
                ) : (
                  <span className="muted">{notification.transactionReference}</span>
                )}
              </td>
            </tr>
          ))}
          {notifications && notifications.content.length === 0 && (
            <tr>
              <td colSpan={3} className="muted">Nothing yet.</td>
            </tr>
          )}
        </tbody>
      </table>

      <div className="btn-row" style={{ marginTop: "1rem" }}>
        <button className="btn btn--secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <button className="btn btn--secondary" disabled={notifications?.last ?? true} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}
