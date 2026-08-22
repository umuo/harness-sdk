import Link from "next/link";
import { notFound } from "next/navigation";
import { StatusBadge } from "../../../components/status-badge";
import { traceStore } from "../../../lib/trace-store";
import type { TraceAttributes, TraceSpan } from "../../../lib/trace-types";
import { formatDuration } from "../../page";

export const dynamic = "force-dynamic";

export default async function TraceDetail({
  params,
}: {
  params: Promise<{ turnId: string }>;
}) {
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
        <Link href="/">Turns</Link><span>/</span><strong>{trace.agentName}</strong>
      </nav>

      <section className="detail-header">
        <div>
          <div className="detail-title-row">
            <span className="agent-glyph large">
              {trace.agentName.slice(0, 1).toUpperCase()}
            </span>
            <div>
              <p className="eyebrow">Agent turn</p>
              <h1>{trace.agentName}</h1>
            </div>
          </div>
          <div className="identifier-row">
            <code>{trace.turnId}</code>
            <StatusBadge status={trace.status} />
          </div>
        </div>
        <div className="detail-duration">
          <span>Total duration</span>
          <strong>{formatDuration(trace.durationNanos)}</strong>
          <small>{new Date(trace.startedAt).toLocaleString()}</small>
        </div>
      </section>

      {trace.errorMessage && (
        <section className="error-banner">
          <strong>{trace.errorType || "Turn error"}</strong>
          <p>{trace.errorMessage}</p>
        </section>
      )}

      <section className="detail-grid">
        <article className="panel stat-strip">
          <DetailStat label="Steps" value={trace.stepCount} />
          <DetailStat label="Model calls" value={trace.modelCallCount} />
          <DetailStat label="Tool calls" value={trace.toolCallCount} />
          <DetailStat label="Tool errors" value={trace.toolErrorCount} danger={trace.toolErrorCount > 0} />
          <DetailStat label="Input tokens" value={trace.usage.inputTokens} />
          <DetailStat label="Output tokens" value={trace.usage.outputTokens} />
        </article>

        <article className="panel metadata-card">
          <div className="panel-heading compact">
            <div><p className="eyebrow">Context</p><h2>Trace metadata</h2></div>
          </div>
          <MetadataRow label="Trace ID" value={trace.traceId} />
          <MetadataRow label="Parent turn" value={trace.parentTurnId || "Root turn"} />
          <MetadataRow label="Parent span" value={trace.parentSpanId || "—"} />
          <MetadataRow label="Stream events" value={String(trace.modelStreamEventCount)} />
          <Attributes value={trace.attributes} empty="No resource attributes" />
        </article>
      </section>

      <section className="panel waterfall-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Execution timeline</p>
            <h2>Turn → step → model / tool</h2>
          </div>
          <div className="legend">
            <span className="legend-step">Step</span>
            <span className="legend-model">Model</span>
            <span className="legend-tool">Tool</span>
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
                    <span><strong>{span.name}</strong><small>{span.kind}</small></span>
                  </div>
                  <div className="span-track">
                    <span
                      className={`span-bar kind-${span.kind.toLowerCase()} ${span.status === "ERROR" ? "span-error" : ""}`}
                      style={{ left: `${left}%`, width: `${width}%` }}
                    />
                  </div>
                  <code>{formatDuration(span.durationNanos)}</code>
                </summary>
                <SpanDetails span={span} />
              </details>
            );
          })}
        </div>
      </section>
    </>
  );
}

function DetailStat({ label, value, danger = false }: { label: string; value: number; danger?: boolean }) {
  return <div className={danger ? "detail-stat danger" : "detail-stat"}><span>{label}</span><strong>{value.toLocaleString()}</strong></div>;
}

function MetadataRow({ label, value }: { label: string; value: string }) {
  return <div className="metadata-row"><span>{label}</span><code title={value}>{value}</code></div>;
}

function SpanDetails({ span }: { span: TraceSpan }) {
  return (
    <div className="span-details">
      <div><span>Span ID</span><code>{span.spanId}</code></div>
      <div><span>Parent</span><code>{span.parentSpanId || "—"}</code></div>
      <div><span>Status</span><code>{span.status}</code></div>
      {span.errorMessage && <div className="span-error-copy"><span>{span.errorType || "Error"}</span><code>{span.errorMessage}</code></div>}
      <Attributes value={span.attributes} empty="No captured attributes" />
    </div>
  );
}

function Attributes({ value, empty }: { value: TraceAttributes; empty: string }) {
  if (Object.keys(value).length === 0) return <p className="empty-attributes">{empty}</p>;
  return <pre className="attributes-json">{JSON.stringify(value, null, 2)}</pre>;
}
