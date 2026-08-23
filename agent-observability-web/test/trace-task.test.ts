import assert from "node:assert/strict";
import test from "node:test";
import { groupTraceTasks } from "../lib/trace-task";
import type { AgentTrace, TraceSpan } from "../lib/trace-types";

test("groups a root Agent and SubAgents into one human-triggered task", () => {
  const root = trace({
    turnId: "root-1",
    traceId: "trace-1",
    agentName: "supervisor",
    input: "Investigate the production failure",
    counts: [2, 2, 1, 0],
    tokens: [10, 5, 15],
  });
  const child = trace({
    turnId: "child-1",
    traceId: "trace-1",
    parentTurnId: "root-1",
    agentName: "researcher",
    status: "FAILED",
    counts: [3, 3, 2, 1],
    tokens: [20, 8, 28],
  });

  const tasks = groupTraceTasks([child, root]);

  assert.equal(tasks.length, 1);
  assert.equal(tasks[0].taskId, "root-1");
  assert.equal(tasks[0].title, "Investigate the production failure");
  assert.equal(tasks[0].rootAgentName, "supervisor");
  assert.deepEqual(tasks[0].agentNames, ["supervisor", "researcher"]);
  assert.equal(tasks[0].status, "COMPLETED");
  assert.equal(tasks[0].stepCount, 5);
  assert.equal(tasks[0].modelCallCount, 5);
  assert.equal(tasks[0].toolCallCount, 3);
  assert.equal(tasks[0].toolErrorCount, 1);
  assert.equal(tasks[0].usage.totalTokens, 43);
});

test("keeps separate caller tasks that use the same Agent", () => {
  const tasks = groupTraceTasks([
    trace({ turnId: "root-1", traceId: "trace-1", agentName: "assistant" }),
    trace({ turnId: "root-2", traceId: "trace-2", agentName: "assistant" }),
  ]);

  assert.equal(tasks.length, 2);
  assert.deepEqual(
    tasks.map((task) => task.taskId).sort(),
    ["root-1", "root-2"],
  );
});

test("parent links keep tasks separate when an external traceId is reused", () => {
  const tasks = groupTraceTasks([
    trace({ turnId: "root-1", traceId: "shared", agentName: "assistant" }),
    trace({ turnId: "root-2", traceId: "shared", agentName: "assistant" }),
    trace({
      turnId: "child-2",
      traceId: "shared",
      parentTurnId: "root-2",
      agentName: "reviewer",
    }),
  ]);

  assert.equal(tasks.length, 2);
  assert.deepEqual(
    tasks.find((task) => task.taskId === "root-2")?.turnIds.sort(),
    ["child-2", "root-2"],
  );
});

function trace({
  turnId,
  traceId,
  agentName,
  parentTurnId = "",
  input = "",
  status = "COMPLETED",
  counts = [1, 1, 0, 0],
  tokens = [1, 1, 2],
}: {
  turnId: string;
  traceId: string;
  agentName: string;
  parentTurnId?: string;
  input?: string;
  status?: string;
  counts?: [number, number, number, number];
  tokens?: [number, number, number];
}): AgentTrace {
  const startedAt = turnId === "root-2"
    ? "2026-08-23T10:01:00Z"
    : "2026-08-23T10:00:00Z";
  const endedAt = turnId.startsWith("root")
    ? new Date(Date.parse(startedAt) + 1_000).toISOString()
    : new Date(Date.parse(startedAt) + 500).toISOString();
  return {
    schemaVersion: "3",
    traceId,
    turnId,
    parentTurnId,
    parentSpanId: parentTurnId ? `tool-${parentTurnId}` : "",
    agentName,
    status,
    startedAt,
    endedAt,
    durationNanos: turnId.startsWith("root") ? 1_000_000_000 : 500_000_000,
    stepCount: counts[0],
    modelCallCount: counts[1],
    toolCallCount: counts[2],
    toolErrorCount: counts[3],
    modelStreamEventCount: 0,
    usage: {
      inputTokens: tokens[0],
      outputTokens: tokens[1],
      totalTokens: tokens[2],
    },
    attributes: {},
    errorType: status === "FAILED" ? "TestError" : "",
    errorMessage: status === "FAILED" ? "child failed" : "",
    spans: [turnSpan(traceId, turnId, agentName, startedAt, endedAt, input)],
    applicationId: "app-1",
    applicationName: "Test app",
  };
}

function turnSpan(
  traceId: string,
  turnId: string,
  agentName: string,
  startedAt: string,
  endedAt: string,
  input: string,
): TraceSpan {
  return {
    traceId,
    spanId: `turn-${turnId}`,
    parentSpanId: "",
    name: `agent.turn ${agentName}`,
    kind: "TURN",
    status: "OK",
    startedAt,
    endedAt,
    durationNanos: 1,
    input: input
      ? { messages: [{ role: "user", content: input }] }
      : { messageCount: 1 },
    output: {},
    sdkInput: {},
    sdkOutput: {},
    attributes: {},
    errorType: "",
    errorMessage: "",
  };
}
