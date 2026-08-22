# Architecture

## Design goal

Agent SDK is a small Java 8 runtime for state-driven LLM agents. It borrows the
useful idea that execution evolves a shared per-run state, but uses a fixed
agent loop instead of a general graph runtime.

```text
AgentRequest
     |
     v
AgentRunner -- creates --> AgentState
                          |
                          v
                     Model Step
                      /      \
             final answer   tool calls
                  |             |
                  v             v
                 END        Tool Step
                                |
                                +----> AgentState ----> Model Step
```

## Main components

- `Agent` is an immutable definition and the user-facing execution facade.
- `AgentRunner` owns execution resources and creates a new loop for every run.
- `AgentLoop` is a fixed state machine with model and tool phases.
- `AgentPlugin` groups ordered lifecycle observers and model/tool
  interceptors. Plugins are frozen when the Agent is built.
- `AgentObservability` reduces lifecycle events into hierarchical Turn, Step,
  Model and Tool spans plus cumulative process-local metrics.
- `AgentState` is mutable but owned by one invocation. Callers receive an
  immutable `AgentStateSnapshot`.
- `ChatModel` is the provider-neutral complete-response contract.
  `StreamingChatModel` extends it with normalized streaming events. Providers
  translate messages and tool definitions but never execute tools.
- `Tool` has one asynchronous execution contract. `AbstractTool<I>` and
  `AbstractAsyncTool<I>` derive schemas from typed input POJOs, while
  annotation-scanned methods offer a compact alternative. Raw definitions are
  retained only as an escape hatch.
- `AgentTool` delegates a tool call to another Agent with a fresh state.
- `McpToolSet` discovers a remote MCP server's Tools and adapts each one to the
  same local `Tool` contract. MCP transport and lifecycle code stay outside
  `agent-core`.
- `SkillRegistry` holds lightweight metadata for file-backed Agent Skills;
  `skill_load` brings instructions and references into a Turn only on demand.

## State ownership

An Agent instance is immutable and may be reused concurrently. AgentState is
never reused. Each root or child invocation receives a new state containing:

- normalized messages;
- structured tool execution records;
- todos;
- metadata;
- working variables;
- execution status, step count and termination information.

Parent and child Agents do not share messages, variables, metadata or todos.
Only the child task, final tool result, deadline/cancellation control data, and
trace lineage cross the boundary.

## Fixed routing

The runtime intentionally contains only these routing decisions:

1. Call the model.
2. If there are no tool calls, complete with the assistant content.
3. Otherwise execute the requested tools.
4. Append tool messages in the model's original call order.
5. Evaluate termination rules and call the model again.

There is no public node API, edge DSL, graph compiler or checkpoint protocol.
Internal model/tool phases can evolve without committing the public API to a
workflow abstraction.

## Progressive Skills

Skills are portable directories headed by `SKILL.md`, not Java Tool bundles.
When an Agent is built, only each Skill's name, description and location are
added to the system prompt. The builder automatically registers one
`skill_load` Tool. A matching task therefore follows the normal fixed loop:

```text
Skill metadata in system prompt
          |
          v
Model calls skill_load(name, resource?)
          |
          v
Instructions/reference become a Tool result
          |
          v
Next model step follows the loaded guidance
```

There is no Skill router or separate execution engine. `allowed-tools` is
descriptive metadata and never registers or grants a Java Tool. Scripts are
ordinary Skill resources and execute only through an explicitly registered
Tool such as `bash`.

## Turn, step and termination semantics

A Turn is one Agent task: one `run`, `runAsync` or `runStreamingAsync`
invocation with its own State. One Step is one model invocation and its
following tool batch. `maxSteps` therefore limits model invocations. Tool calls
are recorded separately and do not each consume a step.

Execution status follows this state machine:

```text
CREATED -> RUNNING -> COMPLETED
                   -> STOPPED
                   -> FAILED
                   -> CANCELLED
```

Model errors fail the invocation. Tool errors either become error tool
messages (`REPORT_TO_MODEL`) or fail the invocation (`FAIL_FAST`). Reaching the
step limit is a normal `STOPPED` result rather than an exception.

Before a Tool result is recorded or appended to messages, the Agent's
`ToolResultPolicy` applies a final model-context bound. The default policy keeps
the beginning and end within a 2,000-line/50-KiB preview and adds explicit
omission metadata. Before shortening an unreferenced result, it persists the
exact content and adds a `ToolOutputReference`. Producer-level pagination or
stream capture can attach an original-source or temporary-file reference; the
policy reuses it instead of creating another copy. Tools that can produce
unbounded data must still bound acquisition—the context policy is a last
defense, not a substitute for streaming or pagination.

## Concurrency

The public asynchronous contract uses `CompletableFuture`. Tool batches can be
sequential or parallel. Parallel results are always reduced into AgentState in
the original call order, which keeps message history deterministic.

Java 8 has no `CompletableFuture.orTimeout`, so timeouts are implemented by
racing the operation with a `ScheduledExecutorService` task.

## Model provider boundary

Wire protocols are isolated from the Agent runtime:

```text
Agent Loop
    |
    v
ChatModel / StreamingChatModel       (agent-core)
    |
    v
AbstractHttpChatModel + HttpTransport (agent-model-http)
    |
    +-- OpenAI-compatible Chat Completions
    +-- OpenAI Responses API
    +-- Anthropic Messages API
```

`agent-model-http` owns JSON POST, SSE framing, cancellation, timeouts and HTTP
error handling, and packages the bundled protocol adapters. Each provider Java
package owns only request mapping, response mapping and stream-event decoding.
A new HTTP provider normally implements the four hooks on
`AbstractHttpChatModel`; it does not modify `agent-core`.

The normalized stream reports response start, text deltas, tool-call start,
tool-argument deltas and usage. Its completion future returns the same
`ModelResponse` shape as a non-streaming call.

## MCP boundary

MCP is an optional Tool source, not a second Agent runtime:

```text
Agent Loop -> ToolRegistry -> Tool
                              ^
                              |
                    McpToolAdapter
                              |
                         McpClient
                              |
                     stdio MCP server
```

`agent-mcp` owns 2026 stateless discovery and per-request metadata, legacy
initialization fallback, paginated Tool discovery, JSON-RPC correlation,
multi round-trip Tool input, timeouts and subprocess shutdown. A required
namespace converts MCP names to valid, collision-resistant local Tool names.
The Agent Loop then treats local and MCP Tools identically, so existing Tool
interceptors, parallel execution, error policy and output limits still apply.

The current official MCP Java SDK requires Java 17 and targets the legacy
2025-11-25 wire protocol, so it cannot supply the Java 8/2026 implementation.
`McpClient` remains narrow enough for a future official-SDK-backed adapter
without changing Core or Agent APIs. See
[MCP client and Tool integration](mcp.md).

## Agent execution events and plugins

`Agent.runStreamingAsync` selects `StreamingChatModel.generateStream` when the
configured model supports it and otherwise falls back to `ChatModel.generate`.
Both paths emit the same Agent lifecycle events:

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

Every event carries a per-Turn sequence number, Turn ID, Agent name and current
Step. Start and terminal Turn events also carry an immutable State snapshot.
`getRunId()` is retained as an alias for `getTurnId()`.

Lifecycle events are facts for tracing, metrics and audit. Listener failures
are isolated so an observer cannot change execution semantics. Operations that
must wrap, rewrite, reject or short-circuit a model/tool call use ordered
`ModelInterceptor` and `ToolInterceptor` chains supplied by `AgentPlugin`.
Interceptors receive immutable invocation data and State snapshots rather than
the live mutable AgentState.

The built-in `AgentObservability` Plugin assembles those facts into immutable,
exporter-neutral trace segments:

```text
Trace ID
  Turn span
    Step span
      Model span
      Tool span *
```

It also aggregates status, call, Token, error and duration counters. Content
capture is bounded and disabled by default. Agent-as-Tool propagates the trace
ID and links the child Turn span to the parent Tool span while retaining
separate State ownership. No OpenTelemetry, metrics backend or logging API is
required by Core; integrations implement the narrow exporter boundary. See
[Agent observability](observability.md).

Cancelling the returned Agent future propagates to the active model stream or
model future, through interceptor futures, and then to the active sequential or
parallel tool batch. See [Lifecycle events and plugins](plugins.md) for the
extension contract and ordering rules.

## Agent as Tool

Agent does not implement Tool directly. `AgentTool` is an adapter because one
Agent may be exposed under different tool names/descriptions, and because a
tool invocation must be translated into a new AgentRequest.

```java
Agent main = Agent.builder()
    .tool(researchAgent)
    .build();
```

The builder overload is shorthand for `tool(researchAgent.asTool())`.
Invocation paths and a maximum sub-agent depth prevent accidental recursive
delegation loops.
