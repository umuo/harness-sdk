# 生命周期事件与插件

## 目的

插件在固定的 Agent 循环（Agent Loop）周围提供小型的、有序的扩展点。
它们不引入图（graph）、事件总线（event bus）、依赖注入容器或动态模块系统。

一个回合（Turn）是一个 Agent 任务，并拥有一个 AgentState。一个步骤（Step）是一次模型请求，加上由该响应请求的工具调用批次：

```text
Turn
  Step 1: Model -> Tool calls -> Tool results
  Step 2: Model -> Final answer
```

## 事件与拦截器

| 机制 | 预期用途 | 是否可能改变执行？ | 失败行为 |
| --- | --- | --- | --- |
| `AgentPlugin.onEvent` | 追踪、指标、审计、UI 更新 | 否 | 隔离并忽略 |
| `ModelInterceptor` | 策略、请求重写、缓存、重试、遥测 | 是 | 导致 Turn 失败 |
| `ToolInterceptor` | 授权、参数重写、Mock/缓存、遥测 | 是 | 遵循配置的 Tool 错误策略 |

事件报告生命周期事实。拦截器是异步链：调用 `chain.proceed(invocation)` 继续执行，或者返回另一个 `CompletableFuture` 来短路底层操作。

## 生命周期

```text
TURN_STARTED
  STEP_STARTED
    MODEL_STARTED
    MODEL_STREAM_EVENT *
    MODEL_COMPLETED
    TOOL_STARTED *
    TOOL_COMPLETED *
  STEP_COMPLETED
TURN_COMPLETED | TURN_STOPPED | TURN_FAILED | TURN_CANCELLED
```

`MODEL_STREAM_EVENT` 仅在配置的模型支持流式输出且 Turn 使用 `runStreamingAsync` 时才会出现。没有工具调用的 Step 仍然会发出 `STEP_COMPLETED`。每个 Turn 都有确切的一个终止 Turn 事件。

事件具有单调递增的每回合序列、时间戳、回合 ID（Turn ID）、Agent 名称和步骤编号。有效负载（Payloads）是特定于类型的。`TURN_STARTED` 和终止事件携带着一个不可变的 State 快照，允许观察者在不访问可变 State 的情况下读取相关元数据。`getRunId()` 仍然作为 `getTurnId()` 的兼容别名保留。

`MODEL_STARTED` 和 `TOOL_STARTED` 描述了在拦截器转换之前由 Agent 循环发出的请求。完成的工具记录同时暴露了 `getCall()`（模型请求的调用）和 `getExecutedCall()`（拦截器转换后的调用）。

## 定义与注册插件

```java
AgentPlugin telemetry = new AgentPlugin() {
    @Override
    public String name() {
        return "telemetry";
    }

    @Override
    public void onEvent(AgentEvent event) {
        metrics.record(event.getType(), event.getTurnId(), event.getStep());
    }

    @Override
    public List<ModelInterceptor> modelInterceptors() {
        return Collections.singletonList((invocation, chain) -> {
            long started = System.nanoTime();
            return chain.proceed(invocation).thenApply(response -> {
                metrics.modelLatency(System.nanoTime() - started);
                return response;
            });
        });
    }
};

Agent agent = Agent.builder()
    .name("assistant")
    .model(chatModel)
    .plugin(telemetry)
    .build();
```

插件事件观察者按注册顺序运行，随后是传递给 `runStreamingAsync` 的每回合监听器。Model 和 Tool 拦截器也遵循插件注册顺序，并像中间件一样嵌套：

```text
Plugin A before
  Plugin B before
    Provider or Tool
  Plugin B after
Plugin A after
```

Agent 及其扩展列表在 `build()` 之后是不可变的。首个版本故意不包含热注册、卸载排序、依赖解析或插件配置 DSL。

对于开箱即用的 Turn/Step/Model/Tool 追踪和累积指标，请注册内置的 `AgentObservability` 插件，而不是在每个应用程序中重新构建事件配对。请参阅 [Agent observability](observability.md)。

## 重写或短路调用

Model 拦截器可以替换不可变的请求：

```java
ModelRequest changed = new ModelRequest(
    invocation.getRequest().getMessages(),
    invocation.getRequest().getTools(),
    customOptions
);
return chain.proceed(invocation.withRequest(changed));
```

Tool 拦截器可以重写工具名称或 JSON 参数，同时保留模型协议所需的调用 ID：

```java
ToolCall original = invocation.getCall();
ToolCall changed = new ToolCall(
    original.getId(), original.getName(), normalizedArguments
);
return chain.proceed(invocation.withCall(changed));
```

要拒绝请求或从缓存提供服务，可以返回已完成的结果而不调用 `proceed`：

```java
return CompletableFuture.completedFuture(
    ToolResult.failure("Tool call rejected by policy")
);
```

拦截器接收的是不可变的 `AgentStateSnapshot`；它们永远不会接收实时的可变 AgentState。Agent 执行的取消会通过拦截器的 future，尽最大努力（on a best-effort basis）传播到活跃的提供程序流、模型请求或工具执行中。

## Agent 作为工具及子回合 (child Turns)

使用 `.tool(childAgent)` 注册的 Agent 与任何普通 Tool 使用相同的 Tool 拦截器链。如果执行到达 `AgentTool`，它会创建一个具有全新 State 及其自身插件的子回合（child Turn）。父节点与子节点不共享可变的 State；它们的 Turn ID 和血统（lineage）元数据保持独立。
