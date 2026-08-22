# Lifecycle Events and Plugins

## Purpose

Plugins provide small, ordered extension points around the fixed Agent Loop.
They do not introduce a graph, event bus, dependency injection container or
dynamic module system.

A Turn is one Agent task and owns one AgentState. A Step is one model request
plus the tool batch requested by that response:

```text
Turn
  Step 1: Model -> Tool calls -> Tool results
  Step 2: Model -> Final answer
```

## Events versus interceptors

| Mechanism | Intended use | May change execution? | Failure behavior |
| --- | --- | --- | --- |
| `AgentPlugin.onEvent` | tracing, metrics, audit, UI updates | no | isolated and ignored |
| `ModelInterceptor` | policy, request rewrite, cache, retry, telemetry | yes | fails the Turn |
| `ToolInterceptor` | authorization, argument rewrite, mock/cache, telemetry | yes | follows the configured Tool error policy |

Events report lifecycle facts. Interceptors are asynchronous chains: call
`chain.proceed(invocation)` to continue, or return another
`CompletableFuture` to short-circuit the underlying operation.

## Lifecycle

```text
TURN_STARTED
  STEP_STARTED
    MODEL_STARTED
    MODEL_STREAM_EVENT *
    MODEL_COMPLETED
    TOOL_STARTED *
    TOOL_COMPLETED *
  STEP_COMPLETED
TURN_COMPLETED | TURN_STOPPED | TURN_FAILED | TURN_CANCELLED
```

`MODEL_STREAM_EVENT` is present only when the configured model supports
streaming and the Turn uses `runStreamingAsync`. A Step without tool calls
still emits `STEP_COMPLETED`. Every Turn has exactly one terminal Turn event.

Events have a monotonically increasing per-Turn sequence, timestamp, Turn ID,
Agent name and Step number. Payloads are type-specific. `TURN_STARTED` and
terminal events carry an immutable State snapshot, allowing observers to read
correlation metadata without accessing mutable State. `getRunId()` remains a
compatibility alias for `getTurnId()`.

`MODEL_STARTED` and `TOOL_STARTED` describe the request emitted by the Agent
Loop before interceptor transformations. The completed tool record exposes
both `getCall()` (the model-requested call) and `getExecutedCall()` (the call
after interceptor transformations).

## Define and register a Plugin

```java
AgentPlugin telemetry = new AgentPlugin() {
    @Override
    public String name() {
        return "telemetry";
    }

    @Override
    public void onEvent(AgentEvent event) {
        metrics.record(event.getType(), event.getTurnId(), event.getStep());
    }

    @Override
    public List<ModelInterceptor> modelInterceptors() {
        return Collections.singletonList((invocation, chain) -> {
            long started = System.nanoTime();
            return chain.proceed(invocation).thenApply(response -> {
                metrics.modelLatency(System.nanoTime() - started);
                return response;
            });
        });
    }
};

Agent agent = Agent.builder()
    .name("assistant")
    .model(chatModel)
    .plugin(telemetry)
    .build();
```

Plugin event observers run in registration order, followed by the per-Turn
listener passed to `runStreamingAsync`. Model and Tool interceptors also follow
plugin registration order and nest like middleware:

```text
Plugin A before
  Plugin B before
    Provider or Tool
  Plugin B after
Plugin A after
```

An Agent and its extension lists are immutable after `build()`. The first
version intentionally has no hot registration, unload ordering, dependency
resolution or plugin configuration DSL.

For ready-to-use Turn/Step/Model/Tool traces and cumulative metrics, register
the built-in `AgentObservability` Plugin instead of rebuilding event pairing in
each application. See [Agent observability](observability.md).

## Rewrite or short-circuit a call

A Model interceptor can replace the immutable request:

```java
ModelRequest changed = new ModelRequest(
    invocation.getRequest().getMessages(),
    invocation.getRequest().getTools(),
    customOptions
);
return chain.proceed(invocation.withRequest(changed));
```

A Tool interceptor can rewrite the tool name or JSON arguments while retaining
the call ID required by the model protocol:

```java
ToolCall original = invocation.getCall();
ToolCall changed = new ToolCall(
    original.getId(), original.getName(), normalizedArguments
);
return chain.proceed(invocation.withCall(changed));
```

To reject or serve a call from a cache, return a completed result without
calling `proceed`:

```java
return CompletableFuture.completedFuture(
    ToolResult.failure("Tool call rejected by policy")
);
```

Interceptors receive an immutable `AgentStateSnapshot`; they never receive the
live mutable AgentState. Cancellation of the Agent execution is propagated
through interceptor futures to the active provider stream, model request or
Tool execution on a best-effort basis.

## Agent-as-Tool and child Turns

An Agent registered with `.tool(childAgent)` uses the same Tool interceptor
chain as any ordinary Tool. If execution reaches the `AgentTool`, it creates a
new child Turn with a fresh State and its own Plugins. Parent and child do not
share mutable State; their Turn IDs and lineage metadata remain distinct.
