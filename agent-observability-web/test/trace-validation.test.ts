import assert from "node:assert/strict";
import test from "node:test";
import { validateTrace } from "../lib/trace-validation";

test("schema v2 preserves structured node input and output", () => {
  const trace = validateTrace(traceDocument("2", {
    input: {
      messages: [{ role: "user", content: "inspect this request" }],
      options: { temperature: 0.2 },
    },
    output: {
      message: { role: "assistant", content: "response body" },
      usage: { inputTokens: 4, outputTokens: 2, totalTokens: 6 },
    },
  }));

  assert.equal(trace.schemaVersion, "2");
  assert.deepEqual(trace.spans[0].input, {
    messages: [{ role: "user", content: "inspect this request" }],
    options: { temperature: 0.2 },
  });
  assert.equal(
    (trace.spans[0].output.message as { content: string }).content,
    "response body",
  );
});

test("schema v1 remains readable with empty node input and output", () => {
  const trace = validateTrace(traceDocument("1", {}));

  assert.equal(trace.schemaVersion, "1");
  assert.deepEqual(trace.spans[0].input, {});
  assert.deepEqual(trace.spans[0].output, {});
});

function traceDocument(
  schemaVersion: "1" | "2",
  spanFields: Record<string, unknown>,
) {
  const timestamp = "2026-08-23T10:00:00Z";
  return {
    schemaVersion,
    traceId: "trace-1",
    turnId: "turn-1",
    parentTurnId: "",
    parentSpanId: "",
    agentName: "assistant",
    status: "COMPLETED",
    startedAt: timestamp,
    endedAt: timestamp,
    durationNanos: 1,
    stepCount: 1,
    modelCallCount: 1,
    toolCallCount: 0,
    toolErrorCount: 0,
    modelStreamEventCount: 0,
    usage: { inputTokens: 4, outputTokens: 2, totalTokens: 6 },
    attributes: {},
    errorType: "",
    errorMessage: "",
    spans: [
      {
        traceId: "trace-1",
        spanId: "model-1",
        parentSpanId: "",
        name: "agent.model",
        kind: "MODEL",
        status: "OK",
        startedAt: timestamp,
        endedAt: timestamp,
        durationNanos: 1,
        attributes: {},
        errorType: "",
        errorMessage: "",
        ...spanFields,
      },
    ],
  };
}
