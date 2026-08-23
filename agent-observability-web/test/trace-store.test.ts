import assert from "node:assert/strict";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { LocalFileTraceStore } from "../lib/trace-store";
import type { AgentTrace } from "../lib/trace-types";

test("trace store deletes one tenant record without touching another", async () => {
  const directory = await fs.mkdtemp(
    path.join(os.tmpdir(), "agent-observability-traces-"),
  );
  try {
    const store = new LocalFileTraceStore(directory, 100);
    await store.save(trace("shared-turn", "app-one"));
    await store.save(trace("shared-turn", "app-two"));

    assert.equal(await store.delete("shared-turn", "app-one"), true);
    assert.equal(await store.get("shared-turn", "app-one"), null);
    assert.equal(
      (await store.get("shared-turn", "app-two"))?.applicationId,
      "app-two",
    );
    assert.equal(await store.delete("shared-turn", "app-one"), false);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test("trace store batch deletion deduplicates identities and reports missing records", async () => {
  const directory = await fs.mkdtemp(
    path.join(os.tmpdir(), "agent-observability-traces-"),
  );
  try {
    const store = new LocalFileTraceStore(directory, 100);
    await store.save(trace("turn-one", "app-one"));
    await store.save(trace("turn-two", "app-one"));

    const result = await store.deleteMany([
      { turnId: "turn-one", applicationId: "app-one" },
      { turnId: "turn-one", applicationId: "app-one" },
      { turnId: "missing", applicationId: "app-one" },
    ]);

    assert.deepEqual(result, { deleted: 1, missing: 1 });
    assert.deepEqual(
      (await store.list()).map((item) => item.turnId),
      ["turn-two"],
    );
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

function trace(turnId: string, applicationId: string): AgentTrace {
  const timestamp = "2026-08-23T10:00:00Z";
  return {
    schemaVersion: "2",
    traceId: `trace-${turnId}`,
    turnId,
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
    usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
    attributes: {},
    errorType: "",
    errorMessage: "",
    spans: [],
    applicationId,
    applicationName: applicationId,
  };
}
