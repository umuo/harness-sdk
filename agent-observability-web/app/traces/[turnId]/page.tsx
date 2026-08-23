import Link from "next/link";
import { notFound } from "next/navigation";
import { StatusBadge } from "../../../components/status-badge";
import { TraceGraph } from "../../../components/trace-graph";
import { applicationStore } from "../../../lib/application-store";
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
import { taskContainingTurn, taskSpans } from "../../../lib/trace-task";
import { traceStore } from "../../../lib/trace-store";
import type { TraceAttributes, TraceSpan } from "../../../lib/trace-types";

export const dynamic = "force-dynamic";

export default async function TraceDetail({
  params,
  searchParams,
}: {
  params: Promise<{ turnId: string }>;
  searchParams: Promise<{ application?: string }>;
}) {
  const locale = await currentLocale();
  const translations = dictionary(locale);
  const copy = translations.detail;
  const { turnId } = await params;
  const applicationId = (await searchParams).application?.trim() ?? "";
  const selectedTrace = await traceStore.get(turnId, applicationId);
  if (!selectedTrace) notFound();
  const application = applicationId
    ? await applicationStore.get(applicationId)
    : null;
  const correlatedTraces = await traceStore.list({
    limit: 1_000,
    applicationId,
    traceId: selectedTrace.traceId,
  });
  const tracesByTurn = new Map(
    correlatedTraces.map((trace) => [trace.turnId, trace] as const),
  );
  tracesByTurn.set(selectedTrace.turnId, selectedTrace);
  const task = taskContainingTurn([...tracesByTurn.values()], turnId);
  if (!task) notFound();
  const rootTrace = tracesByTurn.get(task.rootTurnId) ?? selectedTrace;
  const relatedTraces = [...tracesByTurn.values()].filter((trace) =>
    task.turnIds.includes(trace.turnId),
  );
  const spans = taskSpans(task, relatedTraces);
  const base = spans.length
    ? Math.min(...spans.map((span) => Date.parse(span.startedAt)))
    : Date.parse(task.startedAt);
  const end = spans.length
    ? Math.max(...spans.map((span) => Date.parse(span.endedAt)))
    : Date.parse(task.endedAt);
  const total = Math.max((end - base) * 1_000_000, task.durationNanos, 1);

  return (
    <>
      <nav className="breadcrumbs">
        <Link
          href={
            applicationId
              ? `/?application=${encodeURIComponent(applicationId)}`
              : "/"
          }
        >
          {copy.tasks}
        </Link>
        <span>/</span>
        <strong>{task.title || task.rootAgentName}</strong>
      </nav>

      <section className="detail-header">
        <div>
          <div className="detail-title-row">
            <span className="agent-glyph large">
              {task.rootAgentName.slice(0, 1).toUpperCase()}
            </span>
            <div>
              <p className="eyebrow">{copy.humanTask}</p>
              <h1>{task.title || task.rootAgentName}</h1>
            </div>
          </div>
          <div className="identifier-row">
            <code>{task.taskId}</code>
            <StatusBadge status={task.status} locale={locale} />
          </div>
        </div>
        <div className="detail-duration">
          <span>{copy.totalDuration}</span>
          <strong>{formatDuration(task.durationNanos)}</strong>
          <small>{formatDateTime(task.startedAt, locale)}</small>
        </div>
      </section>

      {task.errorMessage && (
        <section className="error-banner">
          <strong>{task.errorType || copy.taskError}</strong>
          <p>{task.errorMessage}</p>
        </section>
      )}

      <section className="detail-grid">
        <article className="panel stat-strip">
          <DetailStat label={copy.stats.steps} value={task.stepCount} locale={locale} />
          <DetailStat label={copy.stats.modelCalls} value={task.modelCallCount} locale={locale} />
          <DetailStat label={copy.stats.toolCalls} value={task.toolCallCount} locale={locale} />
          <DetailStat
            label={copy.stats.toolErrors}
            value={task.toolErrorCount}
            locale={locale}
            danger={task.toolErrorCount > 0}
          />
          <DetailStat label={copy.stats.inputTokens} value={task.usage.inputTokens} locale={locale} />
          <DetailStat label={copy.stats.outputTokens} value={task.usage.outputTokens} locale={locale} />
        </article>

        <article className="panel metadata-card">
          <div className="panel-heading compact">
            <div>
              <p className="eyebrow">{copy.context}</p>
              <h2>{copy.traceMetadata}</h2>
            </div>
          </div>
          <MetadataRow label={copy.taskId} value={task.taskId} />
          <MetadataRow label={copy.traceId} value={task.traceId} />
          <MetadataRow label={copy.entryAgent} value={task.rootAgentName} />
          <MetadataRow
            label={copy.participatingAgents}
            value={task.agentNames.join(", ")}
          />
          <MetadataRow label={copy.agentTurns} value={String(task.turnIds.length)} />
          <MetadataRow
            label={copy.application}
            value={
              application?.name ??
              (task.applicationName || applicationId || copy.unassignedApplication)
            }
          />
          <MetadataRow label={copy.streamEvents} value={String(task.modelStreamEventCount)} />
          <Attributes value={rootTrace.attributes} empty={copy.noResourceAttributes} />
        </article>
      </section>

      <section className="panel trace-graph-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">{copy.graph}</p>
            <h2>{copy.graphTitle}</h2>
          </div>
          <span className="graph-segment-count">
            {relatedTraces.length} {copy.traceSegments}
          </span>
        </div>
        <TraceGraph
          spans={spans}
          copy={{
            input: copy.input,
            output: copy.output,
            providerInput: copy.providerInput,
            providerOutput: copy.providerOutput,
            sdkInput: copy.sdkInput,
            sdkOutput: copy.sdkOutput,
            metadata: copy.metadata,
            emptyInput: copy.emptyInput,
            emptyOutput: copy.emptyOutput,
            emptySdkInput: copy.emptySdkInput,
            emptySdkOutput: copy.emptySdkOutput,
            contentNotCaptured: copy.contentNotCaptured,
            selectNode: copy.selectNode,
            status: copy.status,
            startedAt: copy.startedAt,
            duration: copy.duration,
            spanId: copy.spanId,
            parentSpanId: copy.parent,
            attributes: copy.attributes,
            kinds: { ...translations.kind },
            statuses: { ...translations.status },
          }}
        />
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
