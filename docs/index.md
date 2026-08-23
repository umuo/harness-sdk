# Agent SDK

A lightweight Java 8 LLM Agent Harness SDK built around a small, state-driven
agent loop.

## Modules

- `agent-core`: provider-neutral agent, state, tool, plugin, observability,
  skill and todo runtime.
- `agent-model-http`: Java 8 HTTP/SSE runtime plus OpenAI-compatible Chat
  Completions, OpenAI Responses API and Anthropic Messages adapters.
- `agent-mcp`: Java 8 MCP 2026-07-28/legacy client, stdio transport, Tool
  discovery, multi round-trip input and local Tool adapters.
- `agent-tools-builtin`: bounded workspace file, glob, edit and opt-in Bash
  tools.
- `agent-examples`: executable examples.
- `agent-observability-web`: standalone Next.js trace ingestion service and
  dashboard (not part of the Maven reactor).

All included model providers support both complete responses and streaming
responses. Agents expose model deltas and execution lifecycle events through
`runStreamingAsync` without changing the fixed state-driven loop.

## Build

```bash
mvn clean verify
```

## Documentation

- [Architecture](architecture.md)
- [API guide](api-guide.md)
- [Tool authoring](tools.md)
- [Agent Skills](skills.md)
- [Model providers and streaming](model-providers.md)
- [MCP client and Tool integration](mcp.md)
- [Lifecycle events and plugins](plugins.md)
- [Agent observability](observability.md)
- [Observability web platform](observability-platform.md)
- [Tool results, preservation and errors](tool-results.md)
- [Built-in workspace tools](builtin-tools.md)
- [Multi-Agent composition](multi-agent.md)
- [Real LLM examples and integration checks](examples.md)
- [OpenAI-compatible provider](openai-provider.md)
- [MVP scope and roadmap](mvp.md)

The project deliberately does not implement a graph DSL, workflow engine,
checkpoint store, RAG system or vector store.
