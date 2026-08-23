"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { formatDuration, formatNumber, formatTimestamp } from "../lib/format";
import type { Locale } from "../lib/i18n";
import type { AgentTask } from "../lib/trace-types";
import { StatusBadge } from "./status-badge";

const DELETE_BATCH_SIZE = 500;

interface TraceTableCopy {
  task: string;
  entryAgent: string;
  untitled: string;
  agentSummary: string;
  application: string;
  unassigned: string;
  status: string;
  started: string;
  latency: string;
  steps: string;
  modelTool: string;
  tokens: string;
  error: string;
  inspect: string;
  selectAll: string;
  selectTask: string;
  selected: string;
  delete: string;
  deleteSelected: string;
  deleting: string;
  deleteConfirm: string;
  deleteSelectedConfirm: string;
  deleteFailed: string;
}

interface ApplicationSummary {
  id: string;
  name: string;
}

export function TraceTable({
  initialTasks,
  applications,
  locale,
  canDelete,
  copy,
}: {
  initialTasks: AgentTask[];
  applications: ApplicationSummary[];
  locale: Locale;
  canDelete: boolean;
  copy: TraceTableCopy;
}) {
  const router = useRouter();
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");

  const tasks = useMemo(
    () => initialTasks.filter((task) => !hidden.has(taskKey(task))),
    [hidden, initialTasks],
  );

  const selectedTasks = useMemo(
    () => tasks.filter((task) => selected.has(taskKey(task))),
    [selected, tasks],
  );
  const allSelected = tasks.length > 0 && selectedTasks.length === tasks.length;

  function toggleAll(checked: boolean) {
    setSelected(checked ? new Set(tasks.map(taskKey)) : new Set());
  }

  function toggle(task: AgentTask, checked: boolean) {
    const key = taskKey(task);
    setSelected((current) => {
      const next = new Set(current);
      if (checked) next.add(key);
      else next.delete(key);
      return next;
    });
  }

  async function removeOne(task: AgentTask) {
    if (!window.confirm(copy.deleteConfirm)) return;
    const key = taskKey(task);
    setBusy(key);
    setError("");
    try {
      await deleteTasks([task]);
      removeLocally([key]);
      router.refresh();
    } catch {
      setError(copy.deleteFailed);
    } finally {
      setBusy("");
    }
  }

  async function removeSelected() {
    if (selectedTasks.length === 0) return;
    const confirmation = copy.deleteSelectedConfirm.replace(
      "{count}",
      String(selectedTasks.length),
    );
    if (!window.confirm(confirmation)) return;

    setBusy("batch");
    setError("");
    try {
      await deleteTasks(selectedTasks);
      removeLocally(selectedTasks.map(taskKey));
    } catch {
      setError(copy.deleteFailed);
    } finally {
      setBusy("");
      router.refresh();
    }
  }

  function removeLocally(keys: string[]) {
    if (keys.length === 0) return;
    const removed = new Set(keys);
    setHidden((current) => new Set([...current, ...removed]));
    setSelected((current) =>
      new Set(Array.from(current).filter((key) => !removed.has(key))),
    );
  }

  return (
    <>
      {canDelete && (
        <div className="trace-selection-bar">
          <span>{selectedTasks.length} {copy.selected}</span>
          <button
            className="danger-action small"
            disabled={selectedTasks.length === 0 || Boolean(busy)}
            onClick={removeSelected}
            type="button"
          >
            {busy === "batch" ? copy.deleting : copy.deleteSelected}
          </button>
          {error && <strong role="alert">{error}</strong>}
        </div>
      )}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              {canDelete && (
                <th className="selection-column">
                  <input
                    aria-label={copy.selectAll}
                    checked={allSelected}
                    disabled={Boolean(busy)}
                    onChange={(event) => toggleAll(event.target.checked)}
                    type="checkbox"
                  />
                </th>
              )}
              <th>{copy.task}</th>
              <th>{copy.entryAgent}</th>
              <th>{copy.application}</th>
              <th>{copy.status}</th>
              <th>{copy.started}</th>
              <th>{copy.latency}</th>
              <th>{copy.steps}</th>
              <th>{copy.modelTool}</th>
              <th>{copy.tokens}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {tasks.map((task) => {
              const key = taskKey(task);
              return (
                <tr key={key}>
                  {canDelete && (
                    <td className="selection-column">
                      <input
                        aria-label={`${copy.selectTask}: ${task.title || task.rootAgentName}`}
                        checked={selected.has(key)}
                        disabled={Boolean(busy)}
                        onChange={(event) => toggle(task, event.target.checked)}
                        type="checkbox"
                      />
                    </td>
                  )}
                  <td>
                    <div className="task-cell">
                      <strong title={task.title}>{task.title || copy.untitled}</strong>
                      <small title={task.taskId}>{shortId(task.taskId)}</small>
                    </div>
                  </td>
                  <td>
                    <div className="agent-cell">
                      <span className="agent-glyph">
                        {task.rootAgentName.slice(0, 1).toUpperCase()}
                      </span>
                      <span>
                        <strong>{task.rootAgentName}</strong>
                        <small>
                          {copy.agentSummary
                            .replace("{agents}", String(task.agentNames.length))
                            .replace("{turns}", String(task.turnIds.length))}
                        </small>
                      </span>
                    </div>
                  </td>
                  <td className="muted">
                    {applicationName(task, applications, copy.unassigned)}
                  </td>
                  <td><StatusBadge status={task.status} locale={locale} /></td>
                  <td className="muted">{formatTimestamp(task.startedAt, locale)}</td>
                  <td>{formatDuration(task.durationNanos)}</td>
                  <td>{task.stepCount}</td>
                  <td>
                    {task.modelCallCount} <span className="slash">/</span>{" "}
                    {task.toolCallCount}
                    {task.toolErrorCount > 0 && (
                      <span className="error-count">
                        {" "}+{task.toolErrorCount} {copy.error}
                      </span>
                    )}
                  </td>
                  <td>{formatNumber(task.usage.totalTokens, locale)}</td>
                  <td>
                    <div className="trace-row-actions">
                      <Link
                        className="inspect-link"
                        href={traceDetailHref(task.rootTurnId, task.applicationId)}
                      >
                        {copy.inspect}
                      </Link>
                      {canDelete && (
                        <button
                          className="trace-delete-action"
                          disabled={Boolean(busy)}
                          onClick={() => removeOne(task)}
                          type="button"
                        >
                          {busy === key ? copy.deleting : copy.delete}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}

async function deleteTasks(tasks: AgentTask[]) {
  const traces = tasks.flatMap((task) =>
    task.turnIds.map((turnId) => ({
      turnId,
      applicationId: task.applicationId,
    })),
  );
  for (let index = 0; index < traces.length; index += DELETE_BATCH_SIZE) {
    await deleteRequest("/api/traces", {
      traces: traces.slice(index, index + DELETE_BATCH_SIZE),
    });
  }
}

async function deleteRequest(path: string, body?: object) {
  const response = await fetch(path, {
    method: "DELETE",
    headers: body ? { "content-type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) throw new Error(`Trace deletion failed: ${response.status}`);
}

function taskKey(task: AgentTask): string {
  return `${task.applicationId}\u0000${task.taskId}`;
}

function applicationName(
  task: AgentTask,
  applications: ApplicationSummary[],
  unassigned: string,
): string {
  if (!task.applicationId) return unassigned;
  return (
    applications.find((application) => application.id === task.applicationId)
      ?.name ?? (task.applicationName || task.applicationId)
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
