import Link from "next/link";
import { RefreshControl } from "../components/refresh-control";
import { StatusBadge } from "../components/status-badge";
import { traceStore } from "../lib/trace-store";
import type { AgentTrace } from "../lib/trace-types";

export const dynamic = "force-dynamic";

type SearchParameters = Promise<{
  status?: string;
  agent?: string;
}>;

export default async function Dashboard({
  searchParams,
}: {
  searchParams: SearchParameters;
}) {
  const parameters = await searchParams;
  const allTraces = await traceStore.list({ limit: 1_000 });
  const status = parameters.status?.trim() ?? "";
  const agent = parameters.agent?.trim() ?? "";
  const traces = filter(allTraces, status, agent);
  const metrics = summarize(allTraces);

  return (
    <>
      <section className="hero">
        <div>
          <p className="eyebrow">Runtime overview</p>
          <h1>Every turn, visible.</h1>
          <p className="hero-copy">
            Follow model calls, tool execution, latency, errors, and token use
            across your Agent hierarchy.
          </p>
        </div>
        <RefreshControl />
      </section>

      <section className="metric-grid" aria-label="Trace summary">
        <Metric label="Turns" value={formatNumber(metrics.turns)} hint="retained" />
        <Metric
          label="Success rate"
          value={percentage(metrics.successRate)}
          hint={`${metrics.failed} non-completed`}
          tone={metrics.failed > 0 ? "warning" : "good"}
        />
        <Metric
          label="P95 latency"
          value={formatDuration(metrics.p95Nanos)}
          hint="turn duration"
        />
        <Metric
          label="Tokens"
          value={formatNumber(metrics.tokens)}
          hint="input + output"
        />
        <Metric
          label="Tool errors"
          value={formatNumber(metrics.toolErrors)}
          hint={`${formatNumber(metrics.toolCalls)} calls`}
          tone={metrics.toolErrors > 0 ? "danger" : "good"}
        />
      </section>

      <section className="panel trace-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">Recent activity</p>
            <h2>Agent turns</h2>
          </div>
          <form className="filters" method="get">
            <input
              aria-label="Filter by agent"
              name="agent"
              defaultValue={agent}
              placeholder="Agent name"
            />
            <select aria-label="Filter by status" name="status" defaultValue={status}>
              <option value="">All statuses</option>
              <option value="COMPLETED">Completed</option>
              <option value="FAILED">Failed</option>
              <option value="STOPPED">Stopped</option>
              <option value="CANCELLED">Cancelled</option>
            </select>
            <button type="submit">Apply</button>
            {(status || agent) && <Link href="/">Clear</Link>}
          </form>
        </div>

        {traces.length === 0 ? (
          <EmptyState hasAny={allTraces.length > 0} />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Agent</th>
                  <th>Status</th>
                  <th>Started</th>
                  <th>Latency</th>
                  <th>Steps</th>
                  <th>Model / Tool</th>
                  <th>Tokens</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {traces.map((trace) => (
                  <tr key={trace.turnId}>
                    <td>
                      <div className="agent-cell">
                        <span className="agent-glyph">
                          {trace.agentName.slice(0, 1).toUpperCase()}
                        </span>
                        <span>
                          <strong>{trace.agentName}</strong>
                          <small title={trace.turnId}>{shortId(trace.turnId)}</small>
                        </span>
                      </div>
                    </td>
                    <td><StatusBadge status={trace.status} /></td>
                    <td className="muted">{formatTimestamp(trace.startedAt)}</td>
                    <td>{formatDuration(trace.durationNanos)}</td>
                    <td>{trace.stepCount}</td>
                    <td>
                      {trace.modelCallCount} <span className="slash">/</span>{" "}
                      {trace.toolCallCount}
                      {trace.toolErrorCount > 0 && (
                        <span className="error-count"> +{trace.toolErrorCount} error</span>
                      )}
                    </td>
                    <td>{formatNumber(trace.usage.totalTokens)}</td>
                    <td>
                      <Link className="inspect-link" href={`/traces/${encodeURIComponent(trace.turnId)}`}>
                        Inspect →
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

function Metric({
  label,
  value,
  hint,
  tone = "neutral",
}: {
  label: string;
  value: string;
  hint: string;
  tone?: "neutral" | "good" | "warning" | "danger";
}) {
  return (
    <article className={`metric metric-${tone}`}>
      <div className="metric-label"><span />{label}</div>
      <strong>{value}</strong>
      <small>{hint}</small>
    </article>
  );
}

function EmptyState({ hasAny }: { hasAny: boolean }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">⌁</div>
      <h3>{hasAny ? "No turns match these filters" : "Waiting for the first turn"}</h3>
      <p>
        {hasAny
          ? "Adjust the agent or status filter to reveal more traces."
          : "Point the Java SDK platform exporter at this service to begin ingesting traces."}
      </p>
      {!hasAny && (
        <pre>{`AgentObservability observability = AgentObservability.platform(
    "http://localhost:3000/api/traces"
);`}</pre>
      )}
    </div>
  );
}

function filter(traces: AgentTrace[], status: string, agent: string) {
  const normalizedStatus = status.toUpperCase();
  const normalizedAgent = agent.toLowerCase();
  return traces.filter(
    (trace) =>
      (!normalizedStatus || trace.status.toUpperCase() === normalizedStatus) &&
      (!normalizedAgent || trace.agentName.toLowerCase().includes(normalizedAgent)),
  );
}

function summarize(traces: AgentTrace[]) {
  const sortedDurations = traces
    .map((trace) => trace.durationNanos)
    .sort((left, right) => left - right);
  const completed = traces.filter((trace) => trace.status === "COMPLETED").length;
  const p95Index = Math.max(0, Math.ceil(sortedDurations.length * 0.95) - 1);
  return {
    turns: traces.length,
    successRate: traces.length ? completed / traces.length : 0,
    failed: traces.length - completed,
    p95Nanos: sortedDurations[p95Index] ?? 0,
    tokens: traces.reduce((sum, trace) => sum + trace.usage.totalTokens, 0),
    toolCalls: traces.reduce((sum, trace) => sum + trace.toolCallCount, 0),
    toolErrors: traces.reduce((sum, trace) => sum + trace.toolErrorCount, 0),
  };
}

export function formatDuration(nanos: number): string {
  const millis = nanos / 1_000_000;
  if (millis < 1) return `${Math.round(nanos / 1_000)}µs`;
  if (millis < 1_000) return `${millis.toFixed(millis < 10 ? 1 : 0)}ms`;
  return `${(millis / 1_000).toFixed(2)}s`;
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(value);
}

function percentage(value: number): string {
  return `${(value * 100).toFixed(value === 1 ? 0 : 1)}%`;
}

function shortId(value: string): string {
  return value.length > 15 ? `${value.slice(0, 7)}…${value.slice(-5)}` : value;
}

function formatTimestamp(value: string): string {
  return new Intl.DateTimeFormat("en", {
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}
