"use client";

import { useMemo, useState } from "react";
import { formatDuration } from "../lib/format";
import type { TraceAttributes, TraceSpan } from "../lib/trace-types";

type InspectorTab =
  | "input"
  | "output"
  | "sdkInput"
  | "sdkOutput"
  | "metadata";

interface GraphNode {
  span: TraceSpan;
  children: GraphNode[];
}

export interface TraceGraphCopy {
  input: string;
  output: string;
  providerInput: string;
  providerOutput: string;
  sdkInput: string;
  sdkOutput: string;
  metadata: string;
  emptyInput: string;
  emptyOutput: string;
  emptySdkInput: string;
  emptySdkOutput: string;
  contentNotCaptured: string;
  selectNode: string;
  status: string;
  startedAt: string;
  duration: string;
  spanId: string;
  parentSpanId: string;
  attributes: string;
  kinds: Record<string, string>;
  statuses: Record<string, string>;
}

export function TraceGraph({
  spans,
  copy,
}: {
  spans: TraceSpan[];
  copy: TraceGraphCopy;
}) {
  const forest = useMemo(() => buildForest(spans), [spans]);
  const firstModel = spans.find((span) => span.kind === "MODEL");
  const [selectedId, setSelectedId] = useState(
    firstModel?.spanId ?? forest[0]?.span.spanId ?? "",
  );
  const [tab, setTab] = useState<InspectorTab>("input");
  const selected =
    spans.find((span) => span.spanId === selectedId) ?? spans[0] ?? null;

  function select(span: TraceSpan) {
    setSelectedId(span.spanId);
    if (Object.keys(span.input).length > 0) setTab("input");
    else if (Object.keys(span.output).length > 0) setTab("output");
    else setTab("metadata");
  }

  return (
    <div className="trace-graph-layout">
      <div className="trace-graph-canvas" role="tree">
        {forest.map((node) => (
          <GraphBranch
            copy={copy}
            key={node.span.spanId}
            node={node}
            onSelect={select}
            selectedId={selected?.spanId ?? ""}
          />
        ))}
      </div>
      <aside className="node-inspector">
        {selected ? (
          <>
            <header className="node-inspector-header">
              <div>
                <span className={`kind-pill kind-pill-${selected.kind.toLowerCase()}`}>
                  {copy.kinds[selected.kind] ?? selected.kind}
                </span>
                <h3>{selected.name}</h3>
              </div>
              <span className={`node-status node-status-${selected.status.toLowerCase()}`}>
                {copy.statuses[selected.status] ?? selected.status}
              </span>
            </header>
            <div className="inspector-tabs" role="tablist">
              <Tab active={tab === "input"} onClick={() => setTab("input")}>
                {hasProviderPayload(selected) ? copy.providerInput : copy.input}
              </Tab>
              <Tab active={tab === "output"} onClick={() => setTab("output")}>
                {hasProviderPayload(selected) ? copy.providerOutput : copy.output}
              </Tab>
              {Object.keys(selected.sdkInput).length > 0 && (
                <Tab
                  active={tab === "sdkInput"}
                  onClick={() => setTab("sdkInput")}
                >
                  {copy.sdkInput}
                </Tab>
              )}
              {Object.keys(selected.sdkOutput).length > 0 && (
                <Tab
                  active={tab === "sdkOutput"}
                  onClick={() => setTab("sdkOutput")}
                >
                  {copy.sdkOutput}
                </Tab>
              )}
              <Tab active={tab === "metadata"} onClick={() => setTab("metadata")}>
                {copy.metadata}
              </Tab>
            </div>
            <InspectorContent copy={copy} span={selected} tab={tab} />
          </>
        ) : (
          <p className="empty-attributes">{copy.selectNode}</p>
        )}
      </aside>
    </div>
  );
}

function GraphBranch({
  node,
  selectedId,
  onSelect,
  copy,
}: {
  node: GraphNode;
  selectedId: string;
  onSelect: (span: TraceSpan) => void;
  copy: TraceGraphCopy;
}) {
  const selected = node.span.spanId === selectedId;
  return (
    <div className="graph-branch" role="treeitem" aria-selected={selected}>
      <button
        className={`graph-node graph-node-${node.span.kind.toLowerCase()} ${selected ? "selected" : ""}`}
        onClick={() => onSelect(node.span)}
        type="button"
      >
        <span className={`graph-node-icon kind-${node.span.kind.toLowerCase()}`}>
          {node.span.kind.slice(0, 1)}
        </span>
        <span className="graph-node-copy">
          <strong>{node.span.name}</strong>
          <small>
            {copy.kinds[node.span.kind] ?? node.span.kind}
            <span>·</span>
            {formatDuration(node.span.durationNanos)}
          </small>
        </span>
        <span className={`graph-node-state graph-node-state-${node.span.status.toLowerCase()}`} />
      </button>
      {node.children.length > 0 && (
        <div className="graph-children" role="group">
          {node.children.map((child) => (
            <GraphBranch
              copy={copy}
              key={child.span.spanId}
              node={child}
              onSelect={onSelect}
              selectedId={selectedId}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function Tab({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: string;
}) {
  return (
    <button
      aria-selected={active}
      className={active ? "active" : ""}
      onClick={onClick}
      role="tab"
      type="button"
    >
      {children}
    </button>
  );
}

function InspectorContent({
  span,
  tab,
  copy,
}: {
  span: TraceSpan;
  tab: InspectorTab;
  copy: TraceGraphCopy;
}) {
  if (tab === "metadata") {
    return (
      <JsonPanel
        empty={copy.selectNode}
        value={{
          [copy.status]: copy.statuses[span.status] ?? span.status,
          [copy.startedAt]: span.startedAt,
          [copy.duration]: formatDuration(span.durationNanos),
          [copy.spanId]: span.spanId,
          [copy.parentSpanId]: span.parentSpanId || null,
          [copy.attributes]: span.attributes,
          ...(span.errorMessage
            ? { errorType: span.errorType, errorMessage: span.errorMessage }
            : {}),
        }}
      />
    );
  }

  const value = tabValue(span, tab);
  const contentCaptured = span.attributes["agent.content.captured"];
  if (
    contentCaptured === false &&
    ((tab === "input" && !hasCapturedInput(span)) ||
      (tab === "output" && !hasCapturedOutput(span)))
  ) {
    return (
      <div className="capture-disabled">
        <strong>{copy.contentNotCaptured}</strong>
        <JsonPanel empty="" value={value} />
      </div>
    );
  }
  return (
    <JsonPanel
      empty={emptyTab(copy, tab)}
      value={value}
    />
  );
}

function tabValue(span: TraceSpan, tab: InspectorTab): TraceAttributes {
  if (tab === "input") return span.input;
  if (tab === "output") return span.output;
  if (tab === "sdkInput") return span.sdkInput;
  if (tab === "sdkOutput") return span.sdkOutput;
  return {};
}

function emptyTab(copy: TraceGraphCopy, tab: InspectorTab): string {
  if (tab === "input") return copy.emptyInput;
  if (tab === "output") return copy.emptyOutput;
  if (tab === "sdkInput") return copy.emptySdkInput;
  if (tab === "sdkOutput") return copy.emptySdkOutput;
  return copy.selectNode;
}

function JsonPanel({
  value,
  empty,
}: {
  value: TraceAttributes;
  empty: string;
}) {
  if (Object.keys(value).length === 0) {
    return <p className="inspector-empty">{empty}</p>;
  }
  return <pre className="inspector-json">{JSON.stringify(value, null, 2)}</pre>;
}

function hasCapturedInput(span: TraceSpan): boolean {
  return (
    Object.keys(span.input).length > 0 ||
    Object.keys(span.sdkInput).length > 0
  );
}

function hasCapturedOutput(span: TraceSpan): boolean {
  return (
    Object.keys(span.output).length > 0 ||
    Object.keys(span.sdkOutput).length > 0
  );
}

function hasProviderPayload(span: TraceSpan): boolean {
  return (
    span.kind === "MODEL" &&
    (Object.keys(span.sdkInput).length > 0 ||
      Object.keys(span.sdkOutput).length > 0 ||
      span.attributes["agent.model.provider.exchange.captured"] === true)
  );
}

function buildForest(spans: TraceSpan[]): GraphNode[] {
  const ordered = [...spans].sort(
    (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
  );
  const nodes = new Map<string, GraphNode>();
  for (const span of ordered) nodes.set(span.spanId, { span, children: [] });

  const roots: GraphNode[] = [];
  for (const span of ordered) {
    const node = nodes.get(span.spanId)!;
    const parent = span.parentSpanId ? nodes.get(span.parentSpanId) : undefined;
    if (parent && parent !== node && !createsCycle(parent, node, nodes)) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
}

function createsCycle(
  parent: GraphNode,
  child: GraphNode,
  nodes: Map<string, GraphNode>,
): boolean {
  let current: GraphNode | undefined = parent;
  const visited = new Set<string>();
  while (current) {
    if (current.span.spanId === child.span.spanId) return true;
    if (visited.has(current.span.spanId)) return true;
    visited.add(current.span.spanId);
    current = current.span.parentSpanId
      ? nodes.get(current.span.parentSpanId)
      : undefined;
  }
  return false;
}
