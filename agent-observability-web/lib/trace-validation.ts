import type { AgentTrace, TraceAttributes, TraceSpan } from "./trace-types";

const MAX_TEXT = 16_384;
const MAX_SPANS = 10_000;

export class TraceValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TraceValidationError";
  }
}

export function validateTrace(input: unknown): AgentTrace {
  const value = record(input, "trace");
  const schemaVersion = text(value.schemaVersion, "schemaVersion", 16);
  if (schemaVersion !== "1" && schemaVersion !== "2") {
    throw new TraceValidationError(
      `Unsupported schemaVersion '${schemaVersion}'`,
    );
  }
  const spansValue = value.spans;
  if (!Array.isArray(spansValue)) {
    throw new TraceValidationError("spans must be an array");
  }
  if (spansValue.length > MAX_SPANS) {
    throw new TraceValidationError(`spans exceeds ${MAX_SPANS} entries`);
  }

  return {
    schemaVersion,
    traceId: text(value.traceId, "traceId", 256),
    turnId: text(value.turnId, "turnId", 256),
    parentTurnId: optionalText(value.parentTurnId, "parentTurnId", 256),
    parentSpanId: optionalText(value.parentSpanId, "parentSpanId", 256),
    agentName: text(value.agentName, "agentName", 512),
    status: text(value.status, "status", 64),
    startedAt: timestamp(value.startedAt, "startedAt"),
    endedAt: timestamp(value.endedAt, "endedAt"),
    durationNanos: nonNegative(value.durationNanos, "durationNanos"),
    stepCount: nonNegative(value.stepCount, "stepCount"),
    modelCallCount: nonNegative(value.modelCallCount, "modelCallCount"),
    toolCallCount: nonNegative(value.toolCallCount, "toolCallCount"),
    toolErrorCount: nonNegative(value.toolErrorCount, "toolErrorCount"),
    modelStreamEventCount: nonNegative(
      value.modelStreamEventCount,
      "modelStreamEventCount",
    ),
    usage: usage(value.usage),
    attributes: attributes(value.attributes, "attributes"),
    errorType: optionalText(value.errorType, "errorType", MAX_TEXT),
    errorMessage: optionalText(value.errorMessage, "errorMessage", MAX_TEXT),
    spans: spansValue.map((span, index) => parseSpan(span, index)),
    applicationId: optionalText(value.applicationId, "applicationId", 128),
    applicationName: optionalText(
      value.applicationName,
      "applicationName",
      512,
    ),
  };
}

function parseSpan(input: unknown, index: number): TraceSpan {
  const prefix = `spans[${index}]`;
  const value = record(input, prefix);
  return {
    traceId: text(value.traceId, `${prefix}.traceId`, 256),
    spanId: text(value.spanId, `${prefix}.spanId`, 256),
    parentSpanId: optionalText(
      value.parentSpanId,
      `${prefix}.parentSpanId`,
      256,
    ),
    name: text(value.name, `${prefix}.name`, 1_024),
    kind: text(value.kind, `${prefix}.kind`, 64),
    status: text(value.status, `${prefix}.status`, 64),
    startedAt: timestamp(value.startedAt, `${prefix}.startedAt`),
    endedAt: timestamp(value.endedAt, `${prefix}.endedAt`),
    durationNanos: nonNegative(
      value.durationNanos,
      `${prefix}.durationNanos`,
    ),
    input: optionalAttributes(value.input, `${prefix}.input`),
    output: optionalAttributes(value.output, `${prefix}.output`),
    attributes: attributes(value.attributes, `${prefix}.attributes`),
    errorType: optionalText(value.errorType, `${prefix}.errorType`, MAX_TEXT),
    errorMessage: optionalText(
      value.errorMessage,
      `${prefix}.errorMessage`,
      MAX_TEXT,
    ),
  };
}

function usage(input: unknown) {
  const value = record(input, "usage");
  return {
    inputTokens: nonNegative(value.inputTokens, "usage.inputTokens"),
    outputTokens: nonNegative(value.outputTokens, "usage.outputTokens"),
    totalTokens: nonNegative(value.totalTokens, "usage.totalTokens"),
  };
}

function attributes(input: unknown, name: string): TraceAttributes {
  return record(input, name);
}

function optionalAttributes(input: unknown, name: string): TraceAttributes {
  if (input === undefined || input === null) return {};
  return attributes(input, name);
}

function record(input: unknown, name: string): Record<string, unknown> {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new TraceValidationError(`${name} must be an object`);
  }
  return input as Record<string, unknown>;
}

function text(input: unknown, name: string, max = MAX_TEXT): string {
  if (typeof input !== "string" || input.trim().length === 0) {
    throw new TraceValidationError(`${name} must be a non-empty string`);
  }
  if (input.length > max) {
    throw new TraceValidationError(`${name} exceeds ${max} characters`);
  }
  return input;
}

function optionalText(input: unknown, name: string, max: number): string {
  if (input === undefined || input === null || input === "") return "";
  if (typeof input !== "string") {
    throw new TraceValidationError(`${name} must be a string`);
  }
  if (input.length > max) {
    throw new TraceValidationError(`${name} exceeds ${max} characters`);
  }
  return input;
}

function nonNegative(input: unknown, name: string): number {
  if (
    typeof input !== "number" ||
    !Number.isFinite(input) ||
    input < 0 ||
    !Number.isInteger(input)
  ) {
    throw new TraceValidationError(`${name} must be a non-negative integer`);
  }
  return input;
}

function timestamp(input: unknown, name: string): string {
  const value = text(input, name, 64);
  if (Number.isNaN(Date.parse(value))) {
    throw new TraceValidationError(`${name} must be an ISO-8601 timestamp`);
  }
  return value;
}
