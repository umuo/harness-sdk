# Agent 可观测性

## 作用域

`agent-core` 包含一个轻量级、提供商中立的可观测性插件 (Plugin)。它在不改变 Agent 执行方式的情况下，将现有的生命周期事件转换为以下有用的输出：

- 每个已完成、已停止、失败或已取消的回合 (Turn) 对应一个不可变的 `AgentTrace`；
- 当启用内容捕获时，每个回合、模型 (Model) 和工具 (Tool) 跨度 (span) 上都有结构化的输入和输出；
- 一个进程本地的 `AgentMetricsSnapshot`，包含回合、步骤 (Step)、模型、工具、Token、错误、持续时间、活跃回合以及导出器失败的计数器。

追踪的层级结构遵循固定的 Agent 循环 (Agent Loop)，而不是发明一种工作流模型：

```text
Turn
  Step 1
    Model
    Tool A
    Tool B
  Step 2
    Model
```

跨度 (Spans) 以扁平的不可变列表形式返回，包含 `spanId` 和 `parentSpanId`，这对于 OpenTelemetry、日志记录、数据库或测试适配器来说非常方便。它们的标识符是不透明的 SDK 标识符；导出器可以将它们转换为其后端所需的标识符格式。

每个 `AgentSpan` 将 `getInput()` 和 `getOutput()` 与索引属性分开暴露。对于绑定的 HTTP 提供商 (Providers)，模型输入和输出包含实际序列化的请求体和原始响应结构。相应的提供商中立的 `ModelRequest` 和 `ModelResponse` 仍然可以通过 `getSdkInput()` 和 `getSdkOutput()` 获取。没有提供商交换的自定义模型继续将其规范的 SDK 负载直接作为输入和输出暴露。工具节点包含解析后的参数、结果内容、结构化错误、元数据以及输出文件引用。

仅用于可观测性的摘要信息永远不会出现在模型跨度的输入或输出中。例如，消息计数、可用工具计数、返回的工具调用计数以及捕获遗漏计数使用 `agent.model.*` 跨度属性代替。因此，节点检查器将提供商的主体、标准化的 SDK 模型和索引的遥测元数据保持分离。HTTP 标头 (Headers) 刻意从不被复制到交换中，因为授权和供应商 API 密钥标头是敏感信息。

## 基本用法

```java
import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.observability.AgentMetricsSnapshot;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.observability.AgentTrace;
import io.github.gitsilence.agent.observability.InMemoryTraceExporter;

InMemoryTraceExporter traces = new InMemoryTraceExporter(1_000);
AgentObservability observability = AgentObservability.builder()
    .exporter(traces)
    .attribute("service.name", "coding-assistant")
    .build();

Agent agent = Agent.builder()
    .name("assistant")
    .description("Observable assistant")
    .model(model)
    .plugin(observability)
    .build();

agent.run("Inspect the project");

AgentTrace latest = traces.getTraces().get(0);
AgentMetricsSnapshot metrics = observability.getMetrics();
System.out.println(latest.getTraceId());
System.out.println(metrics.getTotalTokens());
```

`InMemoryTraceExporter` 是有界的且线程安全的。当它已满时，它会移除最旧的追踪记录并增加 `getDroppedTraceCount()` 的值。它适用于单元测试、本地诊断和小型嵌入式应用程序，而不是作为持久的生产追踪存储。

## 输出模式

SDK 使可观测性的目的地变得明确。选定的模式可以通过 `getMode()` 获取：

可观测性是选择性加入的 (opt-in)：`AgentBuilder` 不会自动注册此插件。没有使用 `.plugin(observability)` 的 Agent 不会执行任何追踪组装、指标收集、日志记录或平台传输。可执行的示例刻意注册了平台可观测性，以便可以检查其真实的提供商、工具、子 Agent (SubAgent)、MCP、Todo、Skill 以及流式处理行为。

```java
// 不进行追踪组装、指标、日志记录或传输工作。
AgentObservability off = AgentObservability.disabled();

// 通过 java.util.logging，每个完成的回合生成一个带有版本的 JSON 文档。
AgentObservability logs = AgentObservability.logging();

// 有界、异步的 HTTP 交付到绑定的 web 平台。
AgentObservability platform = AgentObservability.platform(
    "http://localhost:3000/api/traces",
    System.getenv("AGENT_OBSERVABILITY_API_KEY")
);

// 应用程序定义的目的地。指标和追踪组装保持启用状态。
AgentObservability custom = AgentObservability.builder()
    .exporter(trace -> telemetryBackend.write(trace))
    .build();
```

`platform(...)` 便捷方法启用了有界内容捕获，因此追踪调试器可以立即显示模型的请求和响应。要使平台追踪仅保留元数据，请通过 Builder 配置传输并调用 `.captureContent(false)`。

不需要特定于提供商的可观测性开关。Agent 循环仅标记那些注册了请求交换捕获的插件的模型请求；然后绑定的 HTTP 提供商将其精确的序列化主体附加到该响应中。没有这种插件的情况下进行的模型调用，会保留以前的零捕获行为。

使用 `.plugin(observability)` 准确注册所选的实例。`OFF` 模式在每个生命周期事件上都会立即返回，因此它比空操作的自定义导出器开销更低，并且其本地指标保持为零。

## 导出器

`AgentTraceExporter` 刻意只保留一个小方法：

```java
AgentTraceExporter exporter = trace -> {
    telemetryBackend.write(trace);
};
```

自定义和日志导出调用在终态回合事件之后运行。自定义导出器应快速返回。导出器异常与 Agent 隔离，并由 `AgentMetricsSnapshot.getExporterFailures()` 计数。

`PlatformTraceExporter` 提供了 HTTP 所需的有界后台队列：

```java
PlatformTraceExporter transport = PlatformTraceExporter.builder(
        "https://observability.example.com/api/traces")
    .apiKey(System.getenv("AGENT_OBSERVABILITY_API_KEY"))
    .queueCapacity(2_000)
    .connectTimeout(Duration.ofSeconds(3))
    .readTimeout(Duration.ofSeconds(5))
    .maxAttempts(3)
    .retryDelay(Duration.ofMillis(200))
    .maxPayloadBytes(2 * 1024 * 1024)
    .build();

AgentObservability observability = AgentObservability.builder()
    .platform(transport)
    .captureContent(true)
    .attribute("service.name", "coding-assistant")
    .build();
```

Agent 线程仅向队列提供不可变的追踪记录。当队列已满时，最新的追踪记录将被丢弃，而不是对 Agent 循环施加背压。传输的健康状况可以通过 `getAcceptedCount()`、`getSentCount()`、`getFailedCount()`、`getDroppedCount()`、`getQueuedCount()` 和 `getLastError()` 获取。HTTP 408、429 和 5xx 响应会被重试；其他 4xx 响应会立即失败。

平台构建器将导出器的生命周期所有权转移给 `AgentObservability`。在应用程序关闭期间关闭共享的插件，以在配置的关闭超时时间内排空其队列：

```java
observability.close();
```

对于测试或受控的检查点，`transport.flush(timeout)` 会等待所有当前已接受的追踪记录。Agent 的关闭不会在单个回合完成时自动发生，因为通常有多个 Agent 共享一个可观测性实例。

该模块不依赖于 OpenTelemetry、Micrometer、Spring 或外部日志后端。日志记录模式仅使用 `java.util.logging`。适配器可以映射：

- 将 `AgentTrace` 和 `AgentSpan` 映射到分布式追踪；
- 将 `AgentMetricsSnapshot` 映射到计数器、仪表盘和持续时间总和；
- 将追踪属性映射到后端资源或跨度属性。

## 指标语义

快照包含一个 `AgentObservability` 实例的累积进程本地值：

- 已启动、已完成、已停止、失败、已取消以及当前活跃的回合；
- 步骤、模型调用、工具调用和工具错误；
- 模型提供商报告的输入、输出和总 token 数；
- 累积的回合、模型和工具持续时间（以纳秒为单位）；
- 导出器失败次数。

除了已启动/活跃的回合外，其他值在回合达到终态事件时提交。不报告使用情况的提供商贡献的 token 数为零。指标是无锁快照，而不是直方图或持久的指标存储。

## 内容隐私

除非注册了其插件，否则可观测性将保持禁用状态。自定义构建器和日志记录配置不会捕获提示词、模型响应、工具参数、工具结果或最终答案，除非明确启用。`platform(...)` 便捷方法确实启用了有界内容捕获，因为平台的主要目的是请求级别的调试。使用带有 `.captureContent(false)` 的 Builder 可以获得仅含元数据的平台追踪。

捕获的值在每个文本字段上都是有界的：

```java
AgentObservability observability = AgentObservability.builder()
    .exporter(exporter)
    .captureContent(true)
    .maxCapturedContentCharacters(4_096)
    .build();
```

绑定的 HTTP 模型仅在注册的插件显式请求内容捕获时，才将不包含标头的 `ModelExchange` 附加到每个成功的响应和提供商异常中。在禁用可观测性或仅保留元数据的情况下，原始提供商主体不会被保留，SSE 事件也不会累积。当启用内容捕获时，JSON 主体保留其供应商字段名称和嵌套结构，同时长文本叶子节点、集合大小和嵌套深度保持有界。SSE 响应文本以标准化的 LF 行尾进行捕获。在应用可观测性字段限制之前，每个提供商响应交换都被限制为 2 MiB。授权/API 密钥标头永远不会被附加，且查询字符串或 URL 片段将从捕获的端点中移除。

选择性加入的内容仍然可能包含凭据、个人数据、专有提示词或大型编码值。请在导出器中应用脱敏并限制后端访问。截断可保护内存和遥测数据量；它不是安全边界。

## 根 Agent 与子 Agent 关联

每个根回合都会在状态元数据中接收到一个 `traceId`。调用者可以播种一个外部的关联标识符：

```java
AgentRequest request = AgentRequest.builder()
    .input("Investigate the failure")
    .metadata("traceId", incomingTraceId)
    .build();
```

将 Agent 作为工具 (Agent-as-Tool) 使用时，会自动将追踪 ID、父回合 ID 和父工具调用 ID 传递给子级。在每个参与的 Agent 上注册同一个线程安全的 `AgentObservability` 实例：

```java
AgentObservability observability = AgentObservability.builder()
    .exporter(exporter)
    .build();

Agent researcher = Agent.builder()
    .name("researcher")
    .description("Researches a delegated task")
    .model(researchModel)
    .plugin(observability)
    .build();

Agent supervisor = Agent.builder()
    .name("supervisor")
    .description("Delegates work")
    .model(supervisorModel)
    .tool(researcher)
    .plugin(observability)
    .build();
```

父级和子级仍然拥有独立的可变 Agent 状态。它们导出共享同一个追踪 ID 的独立回合追踪片段；子回合跨度指向父 Agent-工具跨度。绑定的平台将根回合视为一个由调用者触发的任务，将所有后代回合片段分组到一个仪表盘行中，并将它们的跨度合并到一个可点击的调用图中。底层的回合记录保持独立且不可变。任务状态和持续时间使用根回合；计数和 Token 使用量聚合所有参与的 Agent 回合。

## 失败与生命周期行为

- 正常完成会生成 `OK` 跨度；
- 终止条件和最大步骤会生成 `STOPPED` 回合跨度；
- 模型失败和快速失败的工具失败会将所有仍处于打开状态的跨度作为 `ERROR` 关闭；
- 报告的工具失败仅将工具跨度标记为 `ERROR` 并允许 Agent 循环恢复；
- 取消操作会将未完成的跨度作为 `CANCELLED` 关闭；
- 插件/导出器失败绝不改变 Agent 的执行。

当前的传输格式使用 `schemaVersion: "3"`。版本 3 添加了实际的提供商请求/响应负载，以及针对标准化的核心模型的独立 `sdkInput` 和 `sdkOutput` 视图。它由 `AgentTraceJsonCodec` 显式编码，而不是将 Jackson 对 Java 类的表示作为意外的协议暴露出来。绑定的 web 服务继续读取版本 1 和版本 2 的文档。采样、直方图、OpenTelemetry/供应商 SDK 以及生产数据库存储仍然位于核心之外。请参阅 [可观测性 Web 平台](observability-platform.md)。
