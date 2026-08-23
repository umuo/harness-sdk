# OpenAI 提供者

`agent-model-http` 包含两种 OpenAI 协议。两者都支持完整响应、SSE 流、工具定义和工具调用。

## 兼容 OpenAI 的聊天补全 (Chat Completions)

对 OpenAI 聊天补全和兼容服务器使用 `OpenAiCompatibleChatModel`：

```java
StreamingChatModel model = OpenAiCompatibleChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .model(System.getenv("LLM_MODEL"))
    .build();
```

对于兼容的本地或第三方 API，通过版本前缀配置基础 URL。提供者会在内部追加 `/chat/completions`：

```java
StreamingChatModel model = OpenAiCompatibleChatModel.builder()
    .baseUrl("http://localhost:8080/v1")
    .model("local-model")
    .header("X-Custom-Header", "value")
    .build();
```

`OpenAiChatModel` 作为早期类名的源码兼容外观保留，现在也实现了 `StreamingChatModel`。

## OpenAI 响应 (Responses) API

当服务器支持时，使用原生的 Responses 协议。提供者将 `/responses` 追加到配置的基础 URL：

```java
StreamingChatModel model = OpenAiResponsesChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .baseUrl("https://api.openai.com/v1")
    .model(System.getenv("LLM_MODEL"))
    .build();
```

适配器将 Core 消息转换为 Responses 输入项：

- 系统消息成为顶层 `instructions`；
- 用户/助手文本成为消息输入项；
- 助手工具调用成为 `function_call` 项；
- 工具消息成为 `function_call_output` 项。

流事件名称（如输出文本增量和函数参数增量）被标准化为 `ModelStreamEvent` 值。

## 提供者特定选项

两个构建器都支持基础 URL、API 密钥、连接/读取超时、自定义标头和自定义 `HttpTransport`。其他请求字段可以通过 `ModelOptions.extension` 传递。扩展不能替换协议拥有的字段，例如 `model`、`input`/`messages`、`tools` 或 `stream`。

对于需要在流式块中提供使用情况 (usage) 的 Chat Completions 服务器，请显式传递提供者选项：

```java
ModelOptions options = ModelOptions.builder()
    .extension("stream_options",
        Collections.singletonMap("include_usage", true))
    .build();
```
