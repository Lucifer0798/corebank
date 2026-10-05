import { useState } from "react";
import { Link } from "react-router-dom";
import { useNotifications } from "../api/hooks";
import type { Notification } from "../api/types";
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
 * <p>A missed standing-instruction payment has no transaction behind it, so it links to the account
 * page instead, where the instruction itself is listed -- a page both the customer and staff can
 * open.
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
                {pillFor(notification) && (
                  <>
                    {" "}
                    <StatusPill status={pillFor(notification) as string} />
                  </>
                )}
              </td>
              <td>
                {notification.kind !== "TRANSACTION" ? (
                  <Link to={`/accounts/${notification.accountId}`}>Standing instruction</Link>
                ) : staffView ? (
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

/** The marker that stops a row reading as an ordinary payment: a correction, or money that never moved. */
function pillFor(notification: Notification): string | null {
  switch (notification.kind) {
    case "SCHEDULED_TRANSFER_FAILED":
      return "FAILED";
    case "SCHEDULED_TRANSFER_SUSPENDED":
      return "SUSPENDED";
    default:
      return notification.transactionStatus === "REVERSED" ? "REVERSED" : null;
  }
}
