# OpenAI Providers

`agent-model-http` includes two OpenAI protocols. Both support complete
responses, SSE streaming, tool definitions and tool calls.

## OpenAI-compatible Chat Completions

Use `OpenAiCompatibleChatModel` for OpenAI Chat Completions and compatible
servers:

```java
StreamingChatModel model = OpenAiCompatibleChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .model(System.getenv("LLM_MODEL"))
    .build();
```

For a compatible local or third-party endpoint:

```java
StreamingChatModel model = OpenAiCompatibleChatModel.builder()
    .endpoint("http://localhost:8080/v1/chat/completions")
    .model("local-model")
    .header("X-Custom-Header", "value")
    .build();
```

`OpenAiChatModel` remains as a source-compatible facade for the earlier class
name and now also implements `StreamingChatModel`.

## OpenAI Responses API

Use the native Responses protocol when the endpoint supports it:

```java
StreamingChatModel model = OpenAiResponsesChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .model(System.getenv("LLM_MODEL"))
    .build();
```

The adapter converts Core messages to Responses input items:

- system messages become top-level `instructions`;
- user/assistant text becomes message input items;
- assistant tool calls become `function_call` items;
- tool messages become `function_call_output` items.

Streaming event names such as output-text deltas and function-argument deltas
are normalized into `ModelStreamEvent` values.

## Provider-specific options

Both builders support endpoint, API key, connect/read timeout, custom headers
and a custom `HttpTransport`. Additional request fields can be passed through
`ModelOptions.extension`. Extensions cannot replace protocol-owned fields such
as `model`, `input`/`messages`, `tools` or `stream`.

For Chat Completions servers that require usage in streaming chunks, pass the
provider option explicitly:

```java
ModelOptions options = ModelOptions.builder()
    .extension("stream_options",
        Collections.singletonMap("include_usage", true))
    .build();
```
