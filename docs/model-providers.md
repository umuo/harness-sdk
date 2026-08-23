# 模型提供商与流式传输

## 支持的协议

| 提供商类 | 通信协议 | 完整响应 | 流式 | 工具 |
| --- | --- | --- | --- | --- |
| `OpenAiCompatibleChatModel` | 兼容 OpenAI 的 Chat Completions | 是 | 是 | 是 |
| `OpenAiResponsesChatModel` | OpenAI Responses API | 是 | 是 | 是 |
| `AnthropicChatModel` | Anthropic Messages API | 是 | 是 | 是 |

所有绑定的 HTTP 提供商都使用同一个 Maven 依赖项：

```xml
<dependency>
  <groupId>io.github.gitsilence</groupId>
  <artifactId>agent-model-http</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

所有绑定的适配器都位于 `agent-model-http` 中，它依赖于 `agent-core`。
Core 模块从不依赖于 HTTP、JSON 或某个具体的提供商。不同的协议通过 Java 包进行分隔，而不是每个厂商一个独立的 Maven 制品（artifact）。

## 统一契约

`ChatModel` 是最小的提供商契约：

```java
CompletableFuture<ModelResponse> generate(ModelRequest request);
```

`StreamingChatModel` 增加了：

```java
ModelStream generateStream(
    ModelRequest request,
    ModelStreamListener listener
);
```

监听器接收标准化的事件：

- `RESPONSE_STARTED`
- `TEXT_DELTA`
- `TOOL_CALL_STARTED`
- `TOOL_ARGUMENTS_DELTA`
- `USAGE`

`ModelStream.completion()` 会解析为一个完整的 `ModelResponse`，其中包括组装好的文本和工具参数。因此，调用者可以实时渲染增量内容（deltas），并且仍然可以在常规代码中复用最终的响应结果。

提供商的回调函数在传输层执行器（transport executor）上运行。监听器的实现应当快速返回，并将耗时的工作交给它们自己的执行器。监听器抛出异常将会导致流异常终止。

## Anthropic 配置

```java
StreamingChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .baseUrl("https://api.anthropic.com")
    .model(System.getenv("ANTHROPIC_MODEL"))
    .defaultMaxTokens(4096)
    .build();
```

Anthropic 要求必须提供 `max_tokens` 参数；适配器会在 `ModelOptions.maxTokens` 存在时使用它，否则使用 `defaultMaxTokens`。它会将系统消息（system messages）映射到顶层系统字段，将助手的工具调用映射为 `tool_use` 块，并将连续的工具结果放入包含 `tool_result` 块的一条用户消息中。

API 版本默认是 `2023-06-01`，可以通过 `.apiVersion(...)` 进行更改。Beta 版本的请求头可以使用 `.header(...)` 提供。
提供商会自动在 `baseUrl` 后面追加 `/v1/messages`；调用者只需配置 Anthropic API 主机地址或代理前缀，而无需配置 Messages 端点本身。

## 添加另一个 HTTP 提供商

对于使用 SSE 流式传输的 JSON-over-HTTP 提供商，可以通过继承 `AbstractHttpChatModel` 并实现四个专注的方法来完成：

```java
public final class VendorChatModel extends AbstractHttpChatModel {
    protected String providerName() { /* ... */ }
    protected JsonNode encodeRequest(ModelRequest request, boolean stream) { /* ... */ }
    protected ModelResponse decodeResponse(JsonNode response) { /* ... */ }
    protected ModelStreamDecoder newStreamDecoder(ModelStreamListener listener) { /* ... */ }
}
```

共享层处理了 Java 8 的 `HttpURLConnection`、异步执行、JSON 序列化、HTTP 错误、SSE 帧（framing）、取消操作以及超时值。
提供商则依然负责处理身份验证标头（authentication headers）和协议语义。

如果未来的提供商使用 WebSocket、gRPC 或某厂商的 SDK，可以直接实现 `StreamingChatModel` 接口。无需对 Agent 或 Tool API 进行任何更改。

## Agent 集成

常规的 `Agent.run` 和 `runAsync` 方法会调用 `ChatModel.generate`。`Agent.runStreamingAsync` 路径在可用时使用 `StreamingChatModel.generateStream`，将标准化后的增量（deltas）作为 `MODEL_STREAM_EVENT` 转发，然后根据组装好的最终 `ModelResponse` 更新状态（State）。因此，无论是流式执行还是非流式执行，工具路由和状态语义都是完全一致的。
