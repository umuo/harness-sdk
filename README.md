# Agent SDK

A lightweight Java 8 LLM Agent Harness SDK built around a small, state-driven
agent loop.

## Modules

- `agent-core`: provider-neutral agent, state, tool, skill and todo runtime.
- `agent-model-http`: Java 8 HTTP/SSE transport and provider extension base.
- `agent-model-openai`: OpenAI-compatible Chat Completions and OpenAI
  Responses API adapters.
- `agent-model-anthropic`: Anthropic Messages API adapter.
- `agent-examples`: executable examples.

All included model providers support both complete responses and streaming
responses. The core Agent Loop currently consumes complete model responses;
streaming is exposed at the provider-neutral Model API.

## Build

```bash
mvn clean verify
```

## Documentation

- [Architecture](docs/architecture.md)
- [API guide](docs/api-guide.md)
- [Model providers and streaming](docs/model-providers.md)
- [OpenAI-compatible provider](docs/openai-provider.md)
- [MVP scope and roadmap](docs/mvp.md)

The project deliberately does not implement a graph DSL, workflow engine,
checkpoint store, RAG system or vector store.
