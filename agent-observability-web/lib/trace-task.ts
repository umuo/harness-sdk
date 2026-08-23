import type { AgentTask, AgentTrace, TraceSpan } from "./trace-types";

/**
 * Groups persisted per-Agent Turn segments into caller-triggered tasks.
 *
 * The root Turn, rather than the Agent name, is the task identity. Parent Turn
 * links are authoritative; traceId is only a correlation fallback for legacy
 * or temporarily incomplete data.
 */
export function groupTraceTasks(traces: AgentTrace[]): AgentTask[] {
  const tasks: AgentTask[] = [];
  for (const tenantTraces of groupByApplication(traces).values()) {
    const byTurnId = new Map(tenantTraces.map((trace) => [trace.turnId, trace]));
    const roots = tenantTraces.filter((trace) => !trace.parentTurnId);
    const groups = new Map<string, AgentTrace[]>();

    for (const trace of tenantTraces) {
      const root = resolveRoot(trace, byTurnId, roots);
      const key = root.turnId;
      const segments = groups.get(key) ?? [];
      segments.push(trace);
      groups.set(key, segments);
    }

    for (const segments of groups.values()) tasks.push(toTask(segments));
  }
  return tasks.sort(
    (left, right) => Date.parse(right.startedAt) - Date.parse(left.startedAt),
  );
}

export function taskContainingTurn(
  traces: AgentTrace[],
  turnId: string,
): AgentTask | null {
  return (
    groupTraceTasks(traces).find((task) => task.turnIds.includes(turnId)) ?? null
  );
}

function groupByApplication(traces: AgentTrace[]) {
  const grouped = new Map<string, AgentTrace[]>();
  for (const trace of traces) {
    const values = grouped.get(trace.applicationId) ?? [];
    values.push(trace);
    grouped.set(trace.applicationId, values);
  }
  return grouped;
}

function resolveRoot(
  trace: AgentTrace,
  byTurnId: Map<string, AgentTrace>,
  roots: AgentTrace[],
): AgentTrace {
  let current = trace;
  const visited = new Set<string>();
  while (current.parentTurnId && !visited.has(current.turnId)) {
    visited.add(current.turnId);
    const parent = byTurnId.get(current.parentTurnId);
    if (!parent) break;
    current = parent;
  }
  if (!current.parentTurnId) return current;

  const correlated = roots.filter((root) => root.traceId === trace.traceId);
  if (correlated.length === 1) return correlated[0];
  if (correlated.length > 1) {
    return nearestContainingRoot(trace, correlated) ?? current;
  }
  return current;
}

function nearestContainingRoot(
  trace: AgentTrace,
  roots: AgentTrace[],
): AgentTrace | null {
  const started = Date.parse(trace.startedAt);
  const containing = roots.filter(
    (root) =>
      Date.parse(root.startedAt) <= started && Date.parse(root.endedAt) >= started,
  );
  return (
    containing.sort(
      (left, right) => Date.parse(right.startedAt) - Date.parse(left.startedAt),
    )[0] ?? null
  );
}

function toTask(segments: AgentTrace[]): AgentTask {
  const sorted = [...segments].sort(
    (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
  );
  const root = sorted.find((trace) => !trace.parentTurnId) ?? sorted[0];
  const startedAt = root.startedAt;
  const endedAt = root.endedAt;
  const fallbackDuration = Math.max(
    0,
    (Date.parse(latestEndedAt(sorted)) - Date.parse(earliestStartedAt(sorted))) *
      1_000_000,
  );
  const agentNames = Array.from(
    new Set([root.agentName, ...sorted.map((trace) => trace.agentName)]),
  );

  return {
    taskId: root.turnId,
    traceId: root.traceId,
    rootTurnId: root.turnId,
    title: taskTitle(root),
    rootAgentName: root.agentName,
    agentNames,
    turnIds: sorted.map((trace) => trace.turnId),
    status: root.status,
    startedAt,
    endedAt,
    durationNanos: root.durationNanos || fallbackDuration,
    stepCount: sum(sorted, (trace) => trace.stepCount),
    modelCallCount: sum(sorted, (trace) => trace.modelCallCount),
    toolCallCount: sum(sorted, (trace) => trace.toolCallCount),
    toolErrorCount: sum(sorted, (trace) => trace.toolErrorCount),
    modelStreamEventCount: sum(
      sorted,
      (trace) => trace.modelStreamEventCount,
    ),
    usage: {
      inputTokens: sum(sorted, (trace) => trace.usage.inputTokens),
      outputTokens: sum(sorted, (trace) => trace.usage.outputTokens),
      totalTokens: sum(sorted, (trace) => trace.usage.totalTokens),
    },
    errorType: root.errorType,
    errorMessage: root.errorMessage,
    applicationId: root.applicationId,
    applicationName: root.applicationName,
  };
}

function taskTitle(trace: AgentTrace): string {
  const turn = trace.spans.find((span) => span.kind === "TURN");
  const messages = turn?.input.messages;
  if (!Array.isArray(messages)) return "";
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (!isRecord(message) || message.role !== "user") continue;
    const content = message.content;
    if (typeof content !== "string" || !content.trim()) continue;
    return compact(content, 160);
  }
  return "";
}

function compact(value: string, limit: number): string {
  const normalized = value.replace(/\s+/g, " ").trim();
  return normalized.length <= limit
    ? normalized
    : `${normalized.slice(0, limit - 1)}…`;
}

function earliestStartedAt(traces: AgentTrace[]): string {
  return traces.reduce(
    (earliest, trace) =>
      Date.parse(trace.startedAt) < Date.parse(earliest)
        ? trace.startedAt
        : earliest,
    traces[0].startedAt,
  );
}

function latestEndedAt(traces: AgentTrace[]): string {
  return traces.reduce(
    (latest, trace) =>
      Date.parse(trace.endedAt) > Date.parse(latest) ? trace.endedAt : latest,
    traces[0].endedAt,
  );
}

function sum(
  traces: AgentTrace[],
  value: (trace: AgentTrace) => number,
): number {
  return traces.reduce((total, trace) => total + value(trace), 0);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function taskSpans(
  task: AgentTask,
  traces: AgentTrace[],
): TraceSpan[] {
  const turnIds = new Set(task.turnIds);
  const spans = new Map<string, TraceSpan>();
  for (const trace of traces) {
    if (!turnIds.has(trace.turnId)) continue;
    for (const span of trace.spans) spans.set(span.spanId, span);
  }
  return [...spans.values()].sort(
    (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
  );
}
