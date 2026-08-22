# Model Providers and Streaming

## Supported protocols

| Provider class | Wire protocol | Complete | Stream | Tools |
| --- | --- | --- | --- | --- |
| `OpenAiCompatibleChatModel` | OpenAI-compatible Chat Completions | yes | yes | yes |
| `OpenAiResponsesChatModel` | OpenAI Responses API | yes | yes | yes |
| `AnthropicChatModel` | Anthropic Messages API | yes | yes | yes |

Provider code depends on `agent-core` and `agent-model-http`. Core never
depends on a concrete provider.

## Unified contracts

`ChatModel` is the smallest provider contract:

```java
CompletableFuture<ModelResponse> generate(ModelRequest request);
```

`StreamingChatModel` adds:

```java
ModelStream generateStream(
    ModelRequest request,
    ModelStreamListener listener
);
```

The listener receives normalized events:

- `RESPONSE_STARTED`
- `TEXT_DELTA`
- `TOOL_CALL_STARTED`
- `TOOL_ARGUMENTS_DELTA`
- `USAGE`

`ModelStream.completion()` resolves to a complete `ModelResponse`, including
assembled text and tool arguments. Callers can therefore render deltas in real
time and still reuse the final response in ordinary code.

Provider callbacks run on the transport executor. Listener implementations
should return quickly and hand expensive work to their own executor. A listener
exception terminates the stream exceptionally.

## Anthropic configuration

```java
StreamingChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .model(System.getenv("ANTHROPIC_MODEL"))
    .defaultMaxTokens(4096)
    .build();
```

Anthropic requires `max_tokens`; the adapter uses `ModelOptions.maxTokens`
when present and otherwise uses `defaultMaxTokens`. It maps system messages to
the top-level system field, assistant tool calls to `tool_use` blocks, and
consecutive tool results to one user message containing `tool_result` blocks.

The API version defaults to `2023-06-01` and can be changed with
`.apiVersion(...)`. Beta headers can be supplied with `.header(...)`.

## Add another HTTP provider

For a JSON-over-HTTP provider with SSE streaming, extend
`AbstractHttpChatModel` and implement four focused methods:

```java
public final class VendorChatModel extends AbstractHttpChatModel {
    protected String providerName() { /* ... */ }
    protected JsonNode encodeRequest(ModelRequest request, boolean stream) { /* ... */ }
    protected ModelResponse decodeResponse(JsonNode response) { /* ... */ }
    protected ModelStreamDecoder newStreamDecoder(ModelStreamListener listener) { /* ... */ }
}
```

The shared layer handles Java 8 `HttpURLConnection`, asynchronous execution,
JSON serialization, HTTP errors, SSE framing, cancellation and timeout values.
The provider remains responsible for authentication headers and protocol
semantics.

If a future provider uses WebSocket, gRPC or a vendor SDK, implement
`StreamingChatModel` directly. No Agent or Tool API changes are needed.

## Agent integration

Normal `Agent.run` and `runAsync` call `ChatModel.generate`. The
`Agent.runStreamingAsync` path uses `StreamingChatModel.generateStream` when
available, forwards normalized deltas as `MODEL_STREAM_EVENT`, then updates
State from the assembled final `ModelResponse`. Tool routing and State semantics
are therefore identical in streaming and non-streaming executions.
