import Link from "next/link";
import { RefreshControl } from "../components/refresh-control";
import { StatusBadge } from "../components/status-badge";
import { currentLocale } from "../lib/current-locale";
import {
  formatDuration,
  formatNumber,
  formatTimestamp,
  percentage,
} from "../lib/format";
import { dictionary } from "../lib/i18n";
import { applicationStore } from "../lib/application-store";
import { traceStore } from "../lib/trace-store";
import type { AgentTrace } from "../lib/trace-types";

export const dynamic = "force-dynamic";

type SearchParameters = Promise<{
  status?: string;
  agent?: string;
  application?: string;
}>;

export default async function Dashboard({
  searchParams,
}: {
  searchParams: SearchParameters;
}) {
  const locale = await currentLocale();
  const copy = dictionary(locale);
  const dashboard = copy.dashboard;
  const parameters = await searchParams;
  const applications = await applicationStore.list();
  const selectedApplication = parameters.application?.trim() ?? "";
  const allTraces = await traceStore.list({
    limit: 1_000,
    applicationId: selectedApplication || undefined,
  });
  const status = parameters.status?.trim() ?? "";
  const agent = parameters.agent?.trim() ?? "";
  const traces = filter(allTraces, status, agent);
  const metrics = summarize(allTraces);

  return (
    <>
      <section className="hero">
        <div>
          <p className="eyebrow">{dashboard.overview}</p>
          <h1>{dashboard.title}</h1>
          <p className="hero-copy">{dashboard.description}</p>
        </div>
        <RefreshControl
          automaticLabel={copy.refresh.automatic}
          refreshLabel={copy.refresh.button}
        />
      </section>

      <section className="metric-grid" aria-label={dashboard.summaryLabel}>
        <Metric
          label={dashboard.metrics.turns}
          value={formatNumber(metrics.turns, locale)}
          hint={dashboard.metrics.retained}
        />
        <Metric
          label={dashboard.metrics.successRate}
          value={percentage(metrics.successRate)}
          hint={`${metrics.failed} ${dashboard.metrics.nonCompleted}`}
          tone={metrics.failed > 0 ? "warning" : "good"}
        />
        <Metric
          label={dashboard.metrics.p95Latency}
          value={formatDuration(metrics.p95Nanos)}
          hint={dashboard.metrics.turnDuration}
        />
        <Metric
          label={dashboard.metrics.tokens}
          value={formatNumber(metrics.tokens, locale)}
          hint={dashboard.metrics.inputOutput}
        />
        <Metric
          label={dashboard.metrics.toolErrors}
          value={formatNumber(metrics.toolErrors, locale)}
          hint={`${formatNumber(metrics.toolCalls, locale)} ${dashboard.metrics.calls}`}
          tone={metrics.toolErrors > 0 ? "danger" : "good"}
        />
      </section>

      <section className="panel trace-panel">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">{dashboard.recent}</p>
            <h2>{dashboard.turnsTitle}</h2>
          </div>
          <form className="filters" method="get">
            <select
              aria-label={dashboard.filters.applicationLabel}
              name="application"
              defaultValue={selectedApplication}
            >
              <option value="">{dashboard.filters.allApplications}</option>
              {applications.map((application) => (
                <option key={application.id} value={application.id}>
                  {application.name}
                </option>
              ))}
            </select>
            <input
              aria-label={dashboard.filters.agentLabel}
              name="agent"
              defaultValue={agent}
              placeholder={dashboard.filters.agentPlaceholder}
            />
            <select
              aria-label={dashboard.filters.statusLabel}
              name="status"
              defaultValue={status}
            >
              <option value="">{dashboard.filters.allStatuses}</option>
              <option value="COMPLETED">{copy.status.COMPLETED}</option>
              <option value="FAILED">{copy.status.FAILED}</option>
              <option value="STOPPED">{copy.status.STOPPED}</option>
              <option value="CANCELLED">{copy.status.CANCELLED}</option>
            </select>
            <button type="submit">{dashboard.filters.apply}</button>
            {(status || agent || selectedApplication) && (
              <Link href="/">{dashboard.filters.clear}</Link>
            )}
          </form>
        </div>

        {traces.length === 0 ? (
          <EmptyState
            hasAny={allTraces.length > 0}
            noMatch={dashboard.empty.noMatch}
            waiting={dashboard.empty.waiting}
            adjust={dashboard.empty.adjust}
            connect={dashboard.empty.connect}
          />
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>{dashboard.table.agent}</th>
                  <th>{dashboard.table.application}</th>
                  <th>{dashboard.table.status}</th>
                  <th>{dashboard.table.started}</th>
                  <th>{dashboard.table.latency}</th>
                  <th>{dashboard.table.steps}</th>
                  <th>{dashboard.table.modelTool}</th>
                  <th>{dashboard.table.tokens}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {traces.map((trace) => (
                  <tr key={`${trace.applicationId}:${trace.turnId}`}>
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
                    <td className="muted">
                      {applicationName(
                        trace.applicationId,
                        trace.applicationName,
                        applications,
                        dashboard.table.unassigned,
                      )}
                    </td>
                    <td>
                      <StatusBadge status={trace.status} locale={locale} />
                    </td>
                    <td className="muted">
                      {formatTimestamp(trace.startedAt, locale)}
                    </td>
                    <td>{formatDuration(trace.durationNanos)}</td>
                    <td>{trace.stepCount}</td>
                    <td>
                      {trace.modelCallCount} <span className="slash">/</span>{" "}
                      {trace.toolCallCount}
                      {trace.toolErrorCount > 0 && (
                        <span className="error-count">
                          {" "}+{trace.toolErrorCount} {dashboard.table.error}
                        </span>
                      )}
                    </td>
                    <td>{formatNumber(trace.usage.totalTokens, locale)}</td>
                    <td>
                      <Link
                        className="inspect-link"
                        href={traceDetailHref(trace.turnId, trace.applicationId)}
                      >
                        {dashboard.table.inspect}
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

function EmptyState({
  hasAny,
  noMatch,
  waiting,
  adjust,
  connect,
}: {
  hasAny: boolean;
  noMatch: string;
  waiting: string;
  adjust: string;
  connect: string;
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon">⌁</div>
      <h3>{hasAny ? noMatch : waiting}</h3>
      <p>{hasAny ? adjust : connect}</p>
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

function applicationName(
  applicationId: string,
  snapshotName: string,
  applications: Array<{ id: string; name: string }>,
  unassigned: string,
): string {
  if (!applicationId) return unassigned;
  return (
    applications.find((application) => application.id === applicationId)
      ?.name ?? (snapshotName || applicationId)
  );
}

function traceDetailHref(turnId: string, applicationId: string): string {
  const path = `/traces/${encodeURIComponent(turnId)}`;
  return applicationId
    ? `${path}?application=${encodeURIComponent(applicationId)}`
    : path;
}

function shortId(value: string): string {
  return value.length > 15 ? `${value.slice(0, 7)}…${value.slice(-5)}` : value;
}
