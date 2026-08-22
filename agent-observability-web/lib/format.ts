import type { Locale } from "./i18n";

export function formatDuration(nanos: number): string {
  const millis = nanos / 1_000_000;
  if (millis < 1) return `${Math.round(nanos / 1_000)}µs`;
  if (millis < 1_000) return `${millis.toFixed(millis < 10 ? 1 : 0)}ms`;
  return `${(millis / 1_000).toFixed(2)}s`;
}

export function formatNumber(value: number, locale: Locale): string {
  return new Intl.NumberFormat(locale, {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(value);
}

export function formatTimestamp(value: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}

export function formatDateTime(value: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(new Date(value));
}

export function percentage(value: number): string {
  return `${(value * 100).toFixed(value === 1 ? 0 : 1)}%`;
}
