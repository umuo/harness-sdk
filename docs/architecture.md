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
- `AgentState` is mutable but owned by one invocation. Callers receive an
  immutable `AgentStateSnapshot`.
- `ChatModel` is the provider-neutral complete-response contract.
  `StreamingChatModel` extends it with normalized streaming events. Providers
  translate messages and tool definitions but never execute tools.
- `Tool` has one asynchronous execution contract. Synchronous tools are
  adapters scheduled on the runner worker executor.
- `AgentTool` delegates a tool call to another Agent with a fresh state.

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

## Step and termination semantics

One step is one model invocation and its following tool batch. `maxSteps`
therefore limits model invocations. Tool calls are recorded separately and do
not each consume a step.

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
error handling. Each provider owns only request mapping, response mapping and
stream-event decoding. A new HTTP provider normally implements the four hooks
on `AbstractHttpChatModel`; it does not modify `agent-core`.

The normalized stream reports response start, text deltas, tool-call start,
tool-argument deltas and usage. Its completion future returns the same
`ModelResponse` shape as a non-streaming call.

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
