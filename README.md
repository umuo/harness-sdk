# Agent SDK

A lightweight Java 8 LLM Agent Harness SDK built around a small, state-driven
agent loop.

## Modules

- `agent-core`: provider-neutral agent, state, tool, plugin, skill and todo
  runtime.
- `agent-model-http`: Java 8 HTTP/SSE runtime plus OpenAI-compatible Chat
  Completions, OpenAI Responses API and Anthropic Messages adapters.
- `agent-tools-builtin`: bounded workspace file, glob, edit and opt-in Bash
  tools.
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
- [Tool authoring](docs/tools.md)
- [Agent Skills](docs/skills.md)
- [Model providers and streaming](docs/model-providers.md)
- [Lifecycle events and plugins](docs/plugins.md)
- [Tool results, preservation and errors](docs/tool-results.md)
- [Built-in workspace tools](docs/builtin-tools.md)
- [Multi-Agent composition](docs/multi-agent.md)
- [OpenAI-compatible provider](docs/openai-provider.md)
- [MVP scope and roadmap](docs/mvp.md)

The project deliberately does not implement a graph DSL, workflow engine,
checkpoint store, RAG system or vector store.
