import Link from "next/link";
import { notFound } from "next/navigation";
import { StatusBadge } from "../../../components/status-badge";
import { currentLocale } from "../../../lib/current-locale";
import {
  formatDateTime,
  formatDuration,
  formatNumber,
} from "../../../lib/format";
import {
  dictionary,
  kindLabel,
  statusLabel,
  type Locale,
} from "../../../lib/i18n";
import { traceStore } from "../../../lib/trace-store";
import type { TraceAttributes, TraceSpan } from "../../../lib/trace-types";

export const dynamic = "force-dynamic";

export default async function TraceDetail({
  params,
}: {
  params: Promise<{ turnId: string }>;
}) {
  const locale = await currentLocale();
  const copy = dictionary(locale).detail;
  const { turnId } = await params;
  const trace = await traceStore.get(turnId);
  if (!trace) notFound();

  const total = Math.max(trace.durationNanos, 1);
  const base = Date.parse(trace.startedAt);
  const spans = [...trace.spans].sort(
    (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
  );

  return (
    <>
      <nav className="breadcrumbs">
        <Link href="/">{copy.turns}</Link>
        <span>/</span>
        <strong>{trace.agentName}</strong>
      </nav>

      <section className="detail-header">
        <div>
          <div className="detail-title-row">
            <span className="agent-glyph large">
              {trace.agentName.slice(0, 1).toUpperCase()}
            </span>
            <div>
              <p className="eyebrow">{copy.agentTurn}</p>
              <h1>{trace.agentName}</h1>
            </div>
          </div>
          <div className="identifier-row">
            <code>{trace.turnId}</code>
            <StatusBadge status={trace.status} locale={locale} />
          </div>
        </div>
        <div className="detail-duration">
          <span>{copy.totalDuration}</span>
          <strong>{formatDuration(trace.durationNanos)}</strong>
          <small>{formatDateTime(trace.startedAt, locale)}</small>
        </div>
      </section>

      {trace.errorMessage && (
        <section className="error-banner">
          <strong>{trace.errorType || copy.turnError}</strong>
          <p>{trace.errorMessage}</p>
        </section>
      )}

      <section className="detail-grid">
        <article className="panel stat-strip">
          <DetailStat label={copy.stats.steps} value={trace.stepCount} locale={locale} />
          <DetailStat label={copy.stats.modelCalls} value={trace.modelCallCount} locale={locale} />
          <DetailStat label={copy.stats.toolCalls} value={trace.toolCallCount} locale={locale} />
          <DetailStat
            label={copy.stats.toolErrors}
            value={trace.toolErrorCount}
            locale={locale}
            danger={trace.toolErrorCount > 0}
          />
          <DetailStat label={copy.stats.inputTokens} value={trace.usage.inputTokens} locale={locale} />
          <DetailStat label={copy.stats.outputTokens} value={trace.usage.outputTokens} locale={locale} />
        </article>

        <article className="panel metadata-card">
          <div className="panel-heading compact">
            <div>
              <p className="eyebrow">{copy.context}</p>
              <h2>{copy.traceMetadata}</h2>
            </div>
          </div>
          <MetadataRow label={copy.traceId} value={trace.traceId} />
          <MetadataRow
            label={copy.parentTurn}
            value={trace.parentTurnId || copy.rootTurn}
          />
          <MetadataRow label={copy.parentSpan} value={trace.parentSpanId || "—"} />
          <MetadataRow label={copy.streamEvents} value={String(trace.modelStreamEventCount)} />
          <Attributes value={trace.attributes} empty={copy.noResourceAttributes} />
        </article>
      </section>

      <section className="panel waterfall-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">{copy.timeline}</p>
            <h2>{copy.timelineTitle}</h2>
          </div>
          <div className="legend">
            <span className="legend-step">{kindLabel("STEP", locale)}</span>
            <span className="legend-model">{kindLabel("MODEL", locale)}</span>
            <span className="legend-tool">{kindLabel("TOOL", locale)}</span>
          </div>
        </div>
        <div className="timeline-scale">
          <span>0</span><span>25%</span><span>50%</span><span>75%</span><span>{formatDuration(total)}</span>
        </div>
        <div className="waterfall">
          {spans.map((span) => {
            const offsetNanos = Math.max(
              0,
              (Date.parse(span.startedAt) - base) * 1_000_000,
            );
            const left = Math.min(100, (offsetNanos / total) * 100);
            const width = Math.max(0.6, Math.min(100 - left, (span.durationNanos / total) * 100));
            return (
              <details className="span-row" key={span.spanId}>
                <summary>
                  <div className="span-name">
                    <span className={`kind-dot kind-${span.kind.toLowerCase()}`} />
                    <span>
                      <strong>{span.name}</strong>
                      <small>{kindLabel(span.kind, locale)}</small>
                    </span>
                  </div>
                  <div className="span-track">
                    <span
                      className={`span-bar kind-${span.kind.toLowerCase()} ${span.status === "ERROR" ? "span-error" : ""}`}
                      style={{ left: `${left}%`, width: `${width}%` }}
                    />
                  </div>
                  <code>{formatDuration(span.durationNanos)}</code>
                </summary>
                <SpanDetails
                  span={span}
                  locale={locale}
                  labels={{
                    spanId: copy.spanId,
                    parent: copy.parent,
                    status: copy.status,
                    error: copy.error,
                    noCapturedAttributes: copy.noCapturedAttributes,
                  }}
                />
              </details>
            );
          })}
        </div>
      </section>
    </>
  );
}

function DetailStat({
  label,
  value,
  locale,
  danger = false,
}: {
  label: string;
  value: number;
  locale: Locale;
  danger?: boolean;
}) {
  return (
    <div className={danger ? "detail-stat danger" : "detail-stat"}>
      <span>{label}</span>
      <strong>{formatNumber(value, locale)}</strong>
    </div>
  );
}

function MetadataRow({ label, value }: { label: string; value: string }) {
  return <div className="metadata-row"><span>{label}</span><code title={value}>{value}</code></div>;
}

function SpanDetails({
  span,
  locale,
  labels,
}: {
  span: TraceSpan;
  locale: Locale;
  labels: {
    spanId: string;
    parent: string;
    status: string;
    error: string;
    noCapturedAttributes: string;
  };
}) {
  return (
    <div className="span-details">
      <div><span>{labels.spanId}</span><code>{span.spanId}</code></div>
      <div><span>{labels.parent}</span><code>{span.parentSpanId || "—"}</code></div>
      <div><span>{labels.status}</span><code>{statusLabel(span.status, locale)}</code></div>
      {span.errorMessage && (
        <div className="span-error-copy">
          <span>{span.errorType || labels.error}</span>
          <code>{span.errorMessage}</code>
        </div>
      )}
      <Attributes value={span.attributes} empty={labels.noCapturedAttributes} />
    </div>
  );
}

function Attributes({ value, empty }: { value: TraceAttributes; empty: string }) {
  if (Object.keys(value).length === 0) return <p className="empty-attributes">{empty}</p>;
  return <pre className="attributes-json">{JSON.stringify(value, null, 2)}</pre>;
}
