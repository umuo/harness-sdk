import assert from "node:assert/strict";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

test("application keys are isolated, rotatable, revocable, and hash-only", async () => {
  const directory = await fs.mkdtemp(
    path.join(os.tmpdir(), "agent-observability-applications-"),
  );
  process.env.AGENT_OBSERVABILITY_DATA_DIR = directory;
  try {
    const { applicationStore, ApplicationStoreError } = await import(
      "../lib/application-store"
    );

    const first = await applicationStore.create({
      name: "Support Agent",
      description: "Production",
    });
    const second = await applicationStore.create({ name: "Research Agent" });

    assert.match(first.apiKey, /^aoh_app_[0-9a-f]{32}_[A-Za-z0-9_-]{43}$/);
    assert.notEqual(first.apiKey, second.apiKey);
    assert.equal(
      (await applicationStore.authenticate(first.apiKey))?.id,
      first.application.id,
    );
    assert.equal(
      (await applicationStore.authenticate(second.apiKey))?.id,
      second.application.id,
    );
    assert.equal(await applicationStore.authenticate("invalid"), null);

    await assert.rejects(
      applicationStore.create({ name: "support agent" }),
      (error: unknown) =>
        error instanceof ApplicationStoreError && error.code === "CONFLICT",
    );

    const updated = await applicationStore.update(first.application.id, {
      name: "Support Agent v2",
    });
    assert.equal(updated.name, "Support Agent v2");

    const rotated = await applicationStore.rotateKey(first.application.id);
    assert.notEqual(rotated.apiKey, first.apiKey);
    assert.equal(await applicationStore.authenticate(first.apiKey), null);
    assert.equal(
      (await applicationStore.authenticate(rotated.apiKey))?.id,
      first.application.id,
    );

    assert.equal(await applicationStore.delete(second.application.id), true);
    assert.equal(await applicationStore.authenticate(second.apiKey), null);
    assert.equal(await applicationStore.delete(second.application.id), false);

    const stored = JSON.parse(
      await fs.readFile(
        path.join(directory, "_platform", "applications.json"),
        "utf8",
      ),
    ) as { applications: Array<Record<string, unknown>> };
    assert.equal(stored.applications.length, 1);
    assert.equal("apiKey" in stored.applications[0], false);
    assert.match(String(stored.applications[0].keyHash), /^[0-9a-f]{64}$/);
    assert.equal(
      JSON.stringify(stored).includes(rotated.apiKey),
      false,
    );
  } finally {
    delete process.env.AGENT_OBSERVABILITY_DATA_DIR;
    await fs.rm(directory, { recursive: true, force: true });
  }
});
