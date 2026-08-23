import { createHash, randomUUID } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import type {
  AgentTrace,
  TraceListOptions,
  TraceStore,
} from "./trace-types";
import { platformDataDirectory } from "./data-directory";
import { validateTrace } from "./trace-validation";

const DEFAULT_RETENTION = 5_000;

class LocalFileTraceStore implements TraceStore {
  private readonly directory: string;
  private readonly retention: number;
  private writeChain: Promise<void> = Promise.resolve();

  constructor() {
    this.directory = platformDataDirectory();
    this.retention = positiveInteger(
      process.env.AGENT_OBSERVABILITY_RETENTION,
      DEFAULT_RETENTION,
    );
  }

  save(trace: AgentTrace): Promise<void> {
    const operation = this.writeChain.then(() => this.persist(trace));
    this.writeChain = operation.catch(() => undefined);
    return operation;
  }

  async get(turnId: string, applicationId = ""): Promise<AgentTrace | null> {
    if (!turnId || turnId.length > 256) return null;
    try {
      const content = await fs.readFile(
        this.fileFor(turnId, applicationId),
        "utf8",
      );
      const trace = validateTrace(JSON.parse(content));
      return trace.turnId === turnId && trace.applicationId === applicationId
        ? trace
        : null;
    } catch (error) {
      if (isMissing(error)) return null;
      throw error;
    }
  }

  async list(options: TraceListOptions = {}): Promise<AgentTrace[]> {
    await this.writeChain;
    let names: string[];
    try {
      names = (await fs.readdir(this.directory)).filter((name) =>
        name.endsWith(".json"),
      );
    } catch (error) {
      if (isMissing(error)) return [];
      throw error;
    }

    const traces = (
      await Promise.all(
        names.map(async (name) => {
          try {
            const content = await fs.readFile(
              path.join(this.directory, name),
              "utf8",
            );
            return validateTrace(JSON.parse(content));
          } catch {
            return null;
          }
        }),
      )
    ).filter((trace): trace is AgentTrace => trace !== null);

    const status = options.status?.trim().toUpperCase();
    const agentName = options.agentName?.trim().toLowerCase();
    const applicationId = options.applicationId?.trim();
    return traces
      .filter((trace) => !status || trace.status.toUpperCase() === status)
      .filter(
        (trace) =>
          !agentName || trace.agentName.toLowerCase().includes(agentName),
      )
      .filter(
        (trace) =>
          applicationId === undefined || trace.applicationId === applicationId,
      )
      .sort(
        (left, right) =>
          Date.parse(right.startedAt) - Date.parse(left.startedAt),
      )
      .slice(0, Math.min(Math.max(options.limit ?? 100, 1), 1_000));
  }

  private async persist(trace: AgentTrace): Promise<void> {
    await fs.mkdir(this.directory, { recursive: true });
    const target = this.fileFor(trace.turnId, trace.applicationId);
    const temporary = `${target}.${randomUUID()}.tmp`;
    try {
      await fs.writeFile(temporary, JSON.stringify(trace), {
        encoding: "utf8",
        mode: 0o600,
      });
      await fs.rename(temporary, target);
    } finally {
      await fs.rm(temporary, { force: true });
    }
    await this.prune();
  }

  private async prune(): Promise<void> {
    const names = (await fs.readdir(this.directory)).filter((name) =>
      name.endsWith(".json"),
    );
    if (names.length <= this.retention) return;
    const entries = await Promise.all(
      names.map(async (name) => ({
        name,
        modified: (await fs.stat(path.join(this.directory, name))).mtimeMs,
      })),
    );
    entries.sort((left, right) => left.modified - right.modified);
    await Promise.all(
      entries
        .slice(0, entries.length - this.retention)
        .map((entry) => fs.unlink(path.join(this.directory, entry.name))),
    );
  }

  private fileFor(turnId: string, applicationId: string): string {
    const identity = applicationId ? `${applicationId}:${turnId}` : turnId;
    const name = createHash("sha256").update(identity).digest("hex");
    return path.join(this.directory, `${name}.json`);
  }
}

function positiveInteger(value: string | undefined, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function isMissing(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { code?: string }).code === "ENOENT"
  );
}

export const traceStore: TraceStore = new LocalFileTraceStore();
