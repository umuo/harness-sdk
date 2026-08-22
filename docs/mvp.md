# MVP Scope and Roadmap

## Included in 0.1

- Provider-neutral complete-response and streaming model APIs.
- Fixed model/tool Agent Loop.
- Per-invocation AgentState and immutable snapshots.
- Programmatic synchronous and asynchronous tools.
- Lightweight annotation-based tools.
- Immutable ToolRegistry and deterministic ToolExecutor.
- Sequential and parallel tool batches.
- Maximum steps, custom termination conditions and error policies.
- Agent-as-Tool with child-state isolation and recursion guards.
- Static Skills and the built-in Todo Skill.
- Java 8 JSON HTTP and SSE transport abstraction.
- Streaming and non-streaming OpenAI-compatible Chat Completions provider.
- Streaming and non-streaming OpenAI Responses API provider.
- Streaming and non-streaming Anthropic Messages API provider.
- Agent lifecycle and model-delta events through `runStreamingAsync`.
- Build-time Plugins with lifecycle observers and ordered model/tool
  interceptors.
- Cancellation propagation from Agent execution to model and tool operations.
- Lightweight ordered parallel-Agent composition.
- Workspace-scoped `read_file`, `write_file`, `edit`, `glob`, and opt-in
  `bash` Tools.
- Runnable examples and scripted-model unit tests.

## Deliberately excluded

- Graph, edge or workflow DSLs.
- Checkpointing and state persistence.
- A persistent event bus or replayable event store.
- Dynamic plugin loading, hot unload, dependency injection or service
  container semantics.
- RAG and vector stores.
- MCP.
- Human-in-the-loop runtime.
- Dynamic Skill routing.
- Distributed execution.
- Spring integration.

## Likely follow-ups

1. More Provider modules.
2. Tracing integrations built as Agent Plugins.
3. Explicit Handoff control signals plus an AgentRegistry.
4. Optional persistence implemented outside AgentState.

Supervisor, router, parallel-agent and review/debate patterns should remain
compositions of Agent, AgentTool and CompletableFuture. Handoff and swarm need
an explicit transfer-of-control concept and should not be hidden inside normal
tool-return semantics.
