# Agent Observability

## Scope

`agent-core` includes a lightweight, provider-neutral observability Plugin. It
turns existing lifecycle events into two useful outputs without changing Agent
execution:

- one immutable `AgentTrace` per completed, stopped, failed, or cancelled Turn;
- structured input and output on every Turn, Model, and Tool span when content
  capture is enabled;
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

Each `AgentSpan` exposes `getInput()` and `getOutput()` separately from indexed
attributes. For the bundled HTTP Providers, Model input and output contain the
actual serialized request body and raw response structure. The corresponding
provider-neutral `ModelRequest` and `ModelResponse` remain available through
`getSdkInput()` and `getSdkOutput()`. Custom Models without a Provider exchange
continue to expose their canonical SDK payload directly as input and output.
Tool nodes contain parsed arguments, result content, structured errors,
metadata, and output-file references.

Observability-only summaries never appear inside Model span input or output.
For example, message count, available Tool count, returned Tool-call count, and
capture-omission counts use `agent.model.*` span attributes instead. The node
inspector therefore keeps the Provider body, normalized SDK model, and indexed
telemetry metadata separate. HTTP Headers are deliberately never copied into
the exchange because Authorization and vendor API-key headers are sensitive.

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

## Output modes

The SDK makes the observability destination explicit. The selected mode is
available through `getMode()`:

Observability is opt-in: `AgentBuilder` does not register this Plugin
automatically. An Agent without `.plugin(observability)` performs no trace
assembly, metrics collection, logging, or platform transport. The environment
driven example also defaults `AGENT_OBSERVABILITY_MODE` to `OFF`.

```java
// No trace assembly, metrics, logging, or transport work.
AgentObservability off = AgentObservability.disabled();

// One versioned JSON document per completed Turn via java.util.logging.
AgentObservability logs = AgentObservability.logging();

// Bounded, asynchronous HTTP delivery to the bundled web platform.
AgentObservability platform = AgentObservability.platform(
    "http://localhost:3000/api/traces",
    System.getenv("AGENT_OBSERVABILITY_API_KEY")
);

// Application-defined destination. Metrics and trace assembly stay enabled.
AgentObservability custom = AgentObservability.builder()
    .exporter(trace -> telemetryBackend.write(trace))
    .build();
```

The `platform(...)` convenience methods enable bounded content capture so the
trace debugger can display Model requests and responses immediately. To keep
platform traces metadata-only, configure the transport through the Builder
and call `.captureContent(false)`.

No Provider-specific observability switch is required. The Agent Loop marks
only Model requests whose registered Plugins request exchange capture; the
bundled HTTP Provider then attaches its exact serialized body to that response.
Model calls made without such a Plugin retain the previous zero-capture
behavior.

Register exactly the chosen instance with `.plugin(observability)`. `OFF`
returns immediately on every lifecycle event, so it has lower overhead than a
no-op custom exporter and its local metrics remain zero.

## Exporters

`AgentTraceExporter` deliberately has one small method:

```java
AgentTraceExporter exporter = trace -> {
    telemetryBackend.write(trace);
};
```

Custom and logging export calls run after a terminal Turn event. Custom
exporters should return quickly. Exporter exceptions are isolated from the
Agent and counted by `AgentMetricsSnapshot.getExporterFailures()`.

`PlatformTraceExporter` supplies the bounded background queue needed for HTTP:

```java
PlatformTraceExporter transport = PlatformTraceExporter.builder(
        "https://observability.example.com/api/traces")
    .apiKey(System.getenv("AGENT_OBSERVABILITY_API_KEY"))
    .queueCapacity(2_000)
    .connectTimeout(Duration.ofSeconds(3))
    .readTimeout(Duration.ofSeconds(5))
    .maxAttempts(3)
    .retryDelay(Duration.ofMillis(200))
    .maxPayloadBytes(2 * 1024 * 1024)
    .build();

AgentObservability observability = AgentObservability.builder()
    .platform(transport)
    .captureContent(true)
    .attribute("service.name", "coding-assistant")
    .build();
```

The Agent thread only offers an immutable trace to the queue. When the queue is
full, the newest trace is dropped instead of applying backpressure to the
Agent Loop. Transport health is available from `getAcceptedCount()`,
`getSentCount()`, `getFailedCount()`, `getDroppedCount()`, `getQueuedCount()`,
and `getLastError()`. HTTP 408, 429, and 5xx responses are retried; other 4xx
responses fail immediately.

The platform builder transfers exporter lifecycle ownership to
`AgentObservability`. Close the shared plugin during application shutdown to
drain its queue up to the configured shutdown timeout:

```java
observability.close();
```

For tests or a controlled checkpoint, `transport.flush(timeout)` waits for all
currently accepted traces. Agent shutdown does not happen automatically when
an individual Turn completes because one observability instance is normally
shared by many Agents.

This module does not depend on OpenTelemetry, Micrometer, Spring, or an external
logging backend. The logging mode uses only `java.util.logging`. Adapters can
map:

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

Observability remains disabled unless its Plugin is registered. Custom Builder
and logging configurations do not capture prompts, Model responses, Tool
arguments, Tool results, or final answers unless explicitly enabled. The
`platform(...)` convenience methods do enable bounded content capture because
the platform's primary purpose is request-level debugging. Use the Builder
with `.captureContent(false)` for metadata-only platform traces.

Captured values are bounded per text field:

```java
AgentObservability observability = AgentObservability.builder()
    .exporter(exporter)
    .captureContent(true)
    .maxCapturedContentCharacters(4_096)
    .build();
```

Bundled HTTP Models attach a header-free `ModelExchange` to each successful
response and to Provider exceptions only when a registered Plugin explicitly
requests content capture. With observability disabled or metadata-only, raw
Provider bodies are not retained and SSE events are not accumulated. When
content capture is enabled, JSON
bodies retain their vendor field names and nesting while long text leaves,
collection sizes, and nesting depth remain bounded. SSE response text is
captured with normalized LF line endings. Every Provider response exchange is
limited to 2 MiB before the observability field limit is applied.
Authorization/API-key Headers are never attached, and
query strings or URL fragments are removed from captured endpoints.

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
parent Agent-Tool span. The bundled platform merges those segments into one
clickable call graph while retaining separate Turn records.

## Failure and lifecycle behavior

- normal completion produces `OK` spans;
- termination conditions and maximum steps produce `STOPPED` Turn spans;
- model failures and fail-fast Tool failures close all still-open spans as
  `ERROR`;
- reported Tool failures mark only the Tool span as `ERROR` and allow the
  Agent Loop to recover;
- cancellation closes unfinished spans as `CANCELLED`;
- plugin/exporter failures never alter Agent execution.

The current wire format has `schemaVersion: "3"`. Version 3 adds the actual
Provider request/response payload plus separate `sdkInput` and `sdkOutput`
views for the normalized Core model. It is encoded explicitly by
`AgentTraceJsonCodec` instead of exposing Jackson's representation of Java
classes as an accidental protocol. The bundled web service continues to read
version 1 and 2 documents.
Sampling, histograms,
OpenTelemetry/vendor SDKs, and production database storage remain outside
Core. See [Observability web platform](observability-platform.md).
