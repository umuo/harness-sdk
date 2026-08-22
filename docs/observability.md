# Agent Observability

## Scope

`agent-core` includes a lightweight, provider-neutral observability Plugin. It
turns existing lifecycle events into two useful outputs without changing Agent
execution:

- one immutable `AgentTrace` per completed, stopped, failed, or cancelled Turn;
- a process-local `AgentMetricsSnapshot` with Turn, Step, Model, Tool, Token,
  error, duration, active-Turn, and exporter-failure counters.

The trace hierarchy follows the fixed Agent Loop rather than inventing a
workflow model:

```text
Turn
  Step 1
    Model
    Tool A
    Tool B
  Step 2
    Model
```

Spans are returned as a flat immutable list with `spanId` and `parentSpanId`,
which is convenient for OpenTelemetry, logging, database, or test adapters.
Their identifiers are opaque SDK identifiers; an exporter may translate them
to the identifier format required by its backend.

## Basic usage

```java
import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.observability.AgentMetricsSnapshot;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.observability.AgentTrace;
import io.github.gitsilence.agent.observability.InMemoryTraceExporter;

InMemoryTraceExporter traces = new InMemoryTraceExporter(1_000);
AgentObservability observability = AgentObservability.builder()
    .exporter(traces)
    .attribute("service.name", "coding-assistant")
    .build();

Agent agent = Agent.builder()
    .name("assistant")
    .description("Observable assistant")
    .model(model)
    .plugin(observability)
    .build();

agent.run("Inspect the project");

AgentTrace latest = traces.getTraces().get(0);
AgentMetricsSnapshot metrics = observability.getMetrics();
System.out.println(latest.getTraceId());
System.out.println(metrics.getTotalTokens());
```

`InMemoryTraceExporter` is bounded and thread-safe. When full, it removes the
oldest trace and increments `getDroppedTraceCount()`. It is intended for unit
tests, local diagnostics, and small embedded applications—not as a durable
production trace store.

## Exporters

`AgentTraceExporter` deliberately has one small method:

```java
AgentTraceExporter exporter = trace -> {
    telemetryBackend.write(trace);
};
```

The export call is synchronous and runs after a terminal Turn event. Keep it
short or hand the immutable trace to a bounded application queue. Exporter
exceptions are isolated from the Agent and counted by
`AgentMetricsSnapshot.getExporterFailures()`.

This module does not depend on OpenTelemetry, Micrometer, Spring, or a logging
backend. Adapters can map:

- `AgentTrace` and `AgentSpan` to distributed traces;
- `AgentMetricsSnapshot` to counters, gauges, and duration sums;
- trace attributes to backend resource or span attributes.

## Metrics semantics

The snapshot contains cumulative process-local values for one
`AgentObservability` instance:

- Turns started, completed, stopped, failed, cancelled, and currently active;
- Steps, Model calls, Tool calls, and Tool errors;
- input, output, and total tokens reported by model providers;
- cumulative Turn, Model, and Tool duration in nanoseconds;
- exporter failures.

Except for started/active Turns, values are committed when a Turn reaches a
terminal event. A provider that does not report usage contributes zero tokens.
Metrics are lock-free snapshots, not histograms or a persistent metrics store.

## Content privacy

Prompts, model responses, Tool arguments, Tool results, and final answers are
not captured by default. Counts, names, Token usage, bounded errors, statuses,
and correlation fields remain available.

Content capture requires an explicit opt-in and is bounded per attribute:

```java
AgentObservability observability = AgentObservability.builder()
    .exporter(exporter)
    .captureContent(true)
    .maxCapturedContentCharacters(4_096)
    .build();
```

Opt-in content can still contain credentials, personal data, proprietary
prompts, or large encoded values. Apply redaction in the exporter and restrict
backend access. Truncation protects memory and telemetry volume; it is not a
security boundary.

## Root and SubAgent correlation

Every root Turn receives a `traceId` in State metadata. A caller can seed an
external correlation identifier:

```java
AgentRequest request = AgentRequest.builder()
    .input("Investigate the failure")
    .metadata("traceId", incomingTraceId)
    .build();
```

Agent-as-Tool automatically passes the trace ID, parent Turn ID, and parent
Tool-call ID to the child. Register the same thread-safe `AgentObservability`
instance on every participating Agent:

```java
AgentObservability observability = AgentObservability.builder()
    .exporter(exporter)
    .build();

Agent researcher = Agent.builder()
    .name("researcher")
    .description("Researches a delegated task")
    .model(researchModel)
    .plugin(observability)
    .build();

Agent supervisor = Agent.builder()
    .name("supervisor")
    .description("Delegates work")
    .model(supervisorModel)
    .tool(researcher)
    .plugin(observability)
    .build();
```

Parent and child still own separate mutable Agent States. They export separate
Turn trace segments sharing one trace ID; the child Turn span points to the
parent Agent-Tool span.

## Failure and lifecycle behavior

- normal completion produces `OK` spans;
- termination conditions and maximum steps produce `STOPPED` Turn spans;
- model failures and fail-fast Tool failures close all still-open spans as
  `ERROR`;
- reported Tool failures mark only the Tool span as `ERROR` and allow the
  Agent Loop to recover;
- cancellation closes unfinished spans as `CANCELLED`;
- plugin/exporter failures never alter Agent execution.

The first version intentionally does not provide sampling, durable storage,
histograms, an async exporter queue, a telemetry server, or vendor SDK
dependencies. Those remain adapters around this stable trace/export boundary.
