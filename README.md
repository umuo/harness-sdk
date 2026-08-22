# Agent SDK

A lightweight Java 8 LLM Agent Harness SDK built around a small, state-driven
agent loop.

## Modules

- `agent-core`: provider-neutral agent, state, tool, plugin, skill and todo
  runtime.
- `agent-model-http`: Java 8 HTTP/SSE transport and provider extension base.
- `agent-model-openai`: OpenAI-compatible Chat Completions and OpenAI
  Responses API adapters.
- `agent-model-anthropic`: Anthropic Messages API adapter.
- `agent-examples`: executable examples.

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
- [Model providers and streaming](docs/model-providers.md)
- [Lifecycle events and plugins](docs/plugins.md)
- [Tool results, truncation and errors](docs/tool-results.md)
- [Multi-Agent composition](docs/multi-agent.md)
- [OpenAI-compatible provider](docs/openai-provider.md)
- [MVP scope and roadmap](docs/mvp.md)

The project deliberately does not implement a graph DSL, workflow engine,
checkpoint store, RAG system or vector store.
