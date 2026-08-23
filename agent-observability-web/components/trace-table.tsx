"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { formatDuration, formatNumber, formatTimestamp } from "../lib/format";
import type { Locale } from "../lib/i18n";
import type { AgentTrace } from "../lib/trace-types";
import { StatusBadge } from "./status-badge";

const DELETE_BATCH_SIZE = 500;

interface TraceTableCopy {
  agent: string;
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
  selectTrace: string;
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
  initialTraces,
  applications,
  locale,
  canDelete,
  copy,
}: {
  initialTraces: AgentTrace[];
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

  const traces = useMemo(
    () => initialTraces.filter((trace) => !hidden.has(traceKey(trace))),
    [hidden, initialTraces],
  );

  const selectedTraces = useMemo(
    () => traces.filter((trace) => selected.has(traceKey(trace))),
    [selected, traces],
  );
  const allSelected = traces.length > 0 && selectedTraces.length === traces.length;

  function toggleAll(checked: boolean) {
    setSelected(checked ? new Set(traces.map(traceKey)) : new Set());
  }

  function toggle(trace: AgentTrace, checked: boolean) {
    const key = traceKey(trace);
    setSelected((current) => {
      const next = new Set(current);
      if (checked) next.add(key);
      else next.delete(key);
      return next;
    });
  }

  async function removeOne(trace: AgentTrace) {
    if (!window.confirm(copy.deleteConfirm)) return;
    const key = traceKey(trace);
    setBusy(key);
    setError("");
    try {
      const query = trace.applicationId
        ? `?applicationId=${encodeURIComponent(trace.applicationId)}`
        : "";
      await deleteRequest(
        `/api/traces/${encodeURIComponent(trace.turnId)}${query}`,
      );
      removeLocally([key]);
      router.refresh();
    } catch {
      setError(copy.deleteFailed);
    } finally {
      setBusy("");
    }
  }

  async function removeSelected() {
    if (selectedTraces.length === 0) return;
    const confirmation = copy.deleteSelectedConfirm.replace(
      "{count}",
      String(selectedTraces.length),
    );
    if (!window.confirm(confirmation)) return;

    setBusy("batch");
    setError("");
    const removed: string[] = [];
    try {
      for (let index = 0; index < selectedTraces.length; index += DELETE_BATCH_SIZE) {
        const batch = selectedTraces.slice(index, index + DELETE_BATCH_SIZE);
        await deleteRequest("/api/traces", {
          traces: batch.map((trace) => ({
            turnId: trace.turnId,
            applicationId: trace.applicationId,
          })),
        });
        removed.push(...batch.map(traceKey));
      }
    } catch {
      setError(copy.deleteFailed);
    } finally {
      removeLocally(removed);
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
          <span>{selectedTraces.length} {copy.selected}</span>
          <button
            className="danger-action small"
            disabled={selectedTraces.length === 0 || Boolean(busy)}
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
              <th>{copy.agent}</th>
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
            {traces.map((trace) => {
              const key = traceKey(trace);
              return (
                <tr key={key}>
                  {canDelete && (
                    <td className="selection-column">
                      <input
                        aria-label={`${copy.selectTrace}: ${trace.agentName}`}
                        checked={selected.has(key)}
                        disabled={Boolean(busy)}
                        onChange={(event) => toggle(trace, event.target.checked)}
                        type="checkbox"
                      />
                    </td>
                  )}
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
                    {applicationName(trace, applications, copy.unassigned)}
                  </td>
                  <td><StatusBadge status={trace.status} locale={locale} /></td>
                  <td className="muted">{formatTimestamp(trace.startedAt, locale)}</td>
                  <td>{formatDuration(trace.durationNanos)}</td>
                  <td>{trace.stepCount}</td>
                  <td>
                    {trace.modelCallCount} <span className="slash">/</span>{" "}
                    {trace.toolCallCount}
                    {trace.toolErrorCount > 0 && (
                      <span className="error-count">
                        {" "}+{trace.toolErrorCount} {copy.error}
                      </span>
                    )}
                  </td>
                  <td>{formatNumber(trace.usage.totalTokens, locale)}</td>
                  <td>
                    <div className="trace-row-actions">
                      <Link
                        className="inspect-link"
                        href={traceDetailHref(trace.turnId, trace.applicationId)}
                      >
                        {copy.inspect}
                      </Link>
                      {canDelete && (
                        <button
                          className="trace-delete-action"
                          disabled={Boolean(busy)}
                          onClick={() => removeOne(trace)}
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

async function deleteRequest(path: string, body?: object) {
  const response = await fetch(path, {
    method: "DELETE",
    headers: body ? { "content-type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) throw new Error(`Trace deletion failed: ${response.status}`);
}

function traceKey(trace: AgentTrace): string {
  return `${trace.applicationId}\u0000${trace.turnId}`;
}

function applicationName(
  trace: AgentTrace,
  applications: ApplicationSummary[],
  unassigned: string,
): string {
  if (!trace.applicationId) return unassigned;
  return (
    applications.find((application) => application.id === trace.applicationId)
      ?.name ?? (trace.applicationName || trace.applicationId)
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
