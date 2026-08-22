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
- Runnable examples and scripted-model unit tests.

## Deliberately excluded

- Graph, edge or workflow DSLs.
- Checkpointing and state persistence.
- Agent-level streaming events (the Model layer already supports streaming).
- RAG and vector stores.
- MCP.
- Human-in-the-loop runtime.
- Dynamic Skill routing.
- Distributed execution.
- Spring integration.

## Likely follow-ups

1. Agent-level streaming events without changing the core state machine.
2. More Provider modules.
3. Agent listeners and tracing integrations.
4. Explicit Handoff control signals plus an AgentRegistry.
5. Optional persistence implemented outside AgentState.

Supervisor, router, parallel-agent and review/debate patterns should remain
compositions of Agent, AgentTool and CompletableFuture. Handoff and swarm need
an explicit transfer-of-control concept and should not be hidden inside normal
tool-return semantics.
