import { statusLabel, type Locale } from "../lib/i18n";

export function StatusBadge({
  status,
  locale,
}: {
  status: string;
  locale: Locale;
}) {
  const normalized = status.toLowerCase();
  return (
    <span className={`status status-${normalized}`} title={status}>
      {statusLabel(status, locale)}
    </span>
  );
}
