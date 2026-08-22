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
  attributes: TraceAttributes;
  errorType: string;
  errorMessage: string;
}

export interface AgentTrace {
  schemaVersion: "1";
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
}

export interface TraceListOptions {
  limit?: number;
  status?: string;
  agentName?: string;
}

export interface TraceStore {
  save(trace: AgentTrace): Promise<void>;
  get(turnId: string): Promise<AgentTrace | null>;
  list(options?: TraceListOptions): Promise<AgentTrace[]>;
}
