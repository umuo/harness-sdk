import Link from "next/link";
import { RefreshControl } from "../components/refresh-control";
import { TraceTable } from "../components/trace-table";
import { isCurrentAdmin } from "../lib/admin-auth";
import { currentLocale } from "../lib/current-locale";
import {
  formatDuration,
  formatNumber,
  percentage,
} from "../lib/format";
import { dictionary } from "../lib/i18n";
import { applicationStore } from "../lib/application-store";
import { groupTraceTasks } from "../lib/trace-task";
import { traceStore } from "../lib/trace-store";
import type { AgentTask } from "../lib/trace-types";

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
  const allTasks = groupTraceTasks(allTraces);
  const tasks = filter(allTasks, status, agent);
  const metrics = summarize(allTasks);
  const canDelete = await isCurrentAdmin();

  return (
    <>
      <section className="hero" style={{ justifyContent: "flex-end" }}>
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

        {tasks.length === 0 ? (
          <EmptyState
            hasAny={allTasks.length > 0}
            noMatch={dashboard.empty.noMatch}
            waiting={dashboard.empty.waiting}
            adjust={dashboard.empty.adjust}
            connect={dashboard.empty.connect}
          />
        ) : (
          <TraceTable
            applications={applications}
            canDelete={canDelete}
            copy={dashboard.table}
            initialTasks={tasks}
            locale={locale}
          />
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

function filter(tasks: AgentTask[], status: string, agent: string) {
  const normalizedStatus = status.toUpperCase();
  const normalizedAgent = agent.toLowerCase();
  return tasks.filter(
    (task) =>
      (!normalizedStatus || task.status.toUpperCase() === normalizedStatus) &&
      (!normalizedAgent ||
        task.agentNames.some((name) =>
          name.toLowerCase().includes(normalizedAgent),
        )),
  );
}

function summarize(tasks: AgentTask[]) {
  const sortedDurations = tasks
    .map((task) => task.durationNanos)
    .sort((left, right) => left - right);
  const completed = tasks.filter((task) => task.status === "COMPLETED").length;
  const p95Index = Math.max(0, Math.ceil(sortedDurations.length * 0.95) - 1);
  return {
    turns: tasks.length,
    successRate: tasks.length ? completed / tasks.length : 0,
    failed: tasks.length - completed,
    p95Nanos: sortedDurations[p95Index] ?? 0,
    tokens: tasks.reduce((sum, task) => sum + task.usage.totalTokens, 0),
    toolCalls: tasks.reduce((sum, task) => sum + task.toolCallCount, 0),
    toolErrors: tasks.reduce((sum, task) => sum + task.toolErrorCount, 0),
  };
}
