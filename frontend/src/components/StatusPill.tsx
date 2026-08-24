const TONE_BY_STATUS: Record<string, "success" | "warning" | "danger" | "neutral"> = {
  ACTIVE: "success",
  VERIFIED: "success",
  POSTED: "success",
  PENDING: "warning",
  FROZEN: "warning",
  SUSPENDED: "warning",
  REJECTED: "danger",
  CLOSED: "danger",
  REVERSED: "danger",
};

export function StatusPill({ status }: { status: string }) {
  const tone = TONE_BY_STATUS[status] ?? "neutral";
  return <span className={`pill pill--${tone}`}>{status}</span>;
}
