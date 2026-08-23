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

- [Architecture](docs/architecture.md)
- [API guide](docs/api-guide.md)
- [Tool authoring](docs/tools.md)
- [Agent Skills](docs/skills.md)
- [Model providers and streaming](docs/model-providers.md)
- [MCP client and Tool integration](docs/mcp.md)
- [Lifecycle events and plugins](docs/plugins.md)
- [Agent observability](docs/observability.md)
- [Observability web platform](docs/observability-platform.md)
- [Tool results, preservation and errors](docs/tool-results.md)
- [Built-in workspace tools](docs/builtin-tools.md)
- [Multi-Agent composition](docs/multi-agent.md)
- [Real LLM examples and integration checks](docs/examples.md)
- [OpenAI-compatible provider](docs/openai-provider.md)
- [MVP scope and roadmap](docs/mvp.md)

The project deliberately does not implement a graph DSL, workflow engine,
checkpoint store, RAG system or vector store.
