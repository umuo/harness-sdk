export type TraceAttributes = Record<string, unknown>;

export interface TraceUsage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
}

export interface TraceSpan {
  traceId: string;
  spanId: string;
  parentSpanId: string;
  name: string;
  kind: string;
  status: string;
  startedAt: string;
  endedAt: string;
  durationNanos: number;
  input: TraceAttributes;
  output: TraceAttributes;
  sdkInput: TraceAttributes;
  sdkOutput: TraceAttributes;
  attributes: TraceAttributes;
  errorType: string;
  errorMessage: string;
}

export interface AgentTrace {
  schemaVersion: "1" | "2" | "3";
  traceId: string;
  turnId: string;
  parentTurnId: string;
  parentSpanId: string;
  agentName: string;
  status: string;
  startedAt: string;
  endedAt: string;
  durationNanos: number;
  stepCount: number;
  modelCallCount: number;
  toolCallCount: number;
  toolErrorCount: number;
  modelStreamEventCount: number;
  usage: TraceUsage;
  attributes: TraceAttributes;
  errorType: string;
  errorMessage: string;
  spans: TraceSpan[];
  /** Server-assigned application tenancy; absent on the Java wire payload. */
  applicationId: string;
  /** Server-assigned name snapshot retained after an application is deleted. */
  applicationName: string;
}

export interface TraceListOptions {
  limit?: number;
  status?: string;
  agentName?: string;
  applicationId?: string;
  traceId?: string;
}

export interface TraceIdentity {
  turnId: string;
  applicationId?: string;
}

export interface TraceDeleteResult {
  deleted: number;
  missing: number;
}

export interface TraceStore {
  save(trace: AgentTrace): Promise<void>;
  get(turnId: string, applicationId?: string): Promise<AgentTrace | null>;
  list(options?: TraceListOptions): Promise<AgentTrace[]>;
  delete(turnId: string, applicationId?: string): Promise<boolean>;
  deleteMany(identities: TraceIdentity[]): Promise<TraceDeleteResult>;
}
