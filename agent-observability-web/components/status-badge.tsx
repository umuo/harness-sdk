export function StatusBadge({ status }: { status: string }) {
  const normalized = status.toLowerCase();
  return <span className={`status status-${normalized}`}>{status}</span>;
}
