# 架构

## 设计目标

Agent SDK 是一个用于状态驱动型 LLM agent 的轻量级 Java 8 运行时。它借鉴了“执行过程演进共享的单次运行状态”这一实用理念，但使用的是固定的 agent 循环而非通用的图结构运行时。

```text
AgentRequest
     |
     v
AgentRunner -- creates --> AgentState
                          |
                          v
                     Model Step
                      /      \
             final answer   tool calls
                  |             |
                  v             v
                 END        Tool Step
                                |
                                +----> AgentState ----> Model Step
```

## 主要组件

- `Agent` 是不可变的定义，也是面向用户的执行门面 (facade)。
- `AgentRunner` 拥有执行资源，并为每次运行创建一个新的循环。
- `AgentLoop` 是一个具有模型和工具阶段的固定状态机。
- `AgentPlugin` 将有序的生命周期观察者和模型/工具拦截器组合在一起。在 Agent 构建时，插件会被冻结。
- `AgentObservability` 将生命周期事件归约为分层的回合 (Turn)、步骤 (Step)、模型 (Model) 和工具 (Tool) 跨度 (spans)，外加累积的进程本地指标。
- `AgentState` 是可变的，但由一次调用所拥有。调用者会收到一个不可变的 `AgentStateSnapshot`。
- `ChatModel` 是与提供商无关的完整响应契约。`StreamingChatModel` 通过规范化的流式事件对其进行了扩展。提供商负责转换消息和工具定义，但从不执行工具。
- `Tool` 具有一个异步执行契约。`AbstractTool<I>` 和 `AbstractAsyncTool<I>` 从类型化的输入 POJO 派生出架构 (schemas)，而注解扫描的方法则提供了一种紧凑的替代方案。原始定义仅保留作为逃生舱口 (escape hatch)。
- `AgentTool` 将工具调用委托给另一个具有全新状态的 Agent。
- `McpToolSet` 发现远程 MCP 服务器的工具，并将每个工具适配为相同的本地 `Tool` 契约。MCP 的传输和生命周期代码保持在 `agent-core` 之外。
- `SkillRegistry` 为基于文件的 Agent Skills (技能) 保存轻量级元数据；`skill_load` 仅在按需时才会将指令和参考信息带入回合 (Turn) 中。

## 状态所有权

Agent 实例是不可变的，并且可以被并发重用。AgentState 则绝不会被重用。每个根调用或子调用都会收到一个包含以下内容的新状态：

- 规范化的消息；
- 结构化的工具执行记录；
- 待办事项 (todos)；
- 元数据；
- 工作变量；
- 执行状态、步骤计数和终止信息。

父 Agent 和子 Agent 不共享消息、变量、元数据或待办事项。只有子任务、最终工具结果、截止时间/取消控制数据以及追踪谱系 (trace lineage) 会跨越边界。

## 固定路由

运行时特意仅包含以下路由决策：

1. 调用模型。
2. 如果没有工具调用，则使用助手的生成内容来完成任务。
3. 否则执行请求的工具。
4. 按照模型原始调用的顺序追加工具消息。
5. 评估终止规则并再次调用模型。

这里没有公共的节点 API、边缘 DSL、图编译器或检查点 (checkpoint) 协议。内部的模型/工具阶段可以在不将公共 API 绑定到工作流抽象的情况下进行演进。

## 渐进式技能 (Progressive Skills)

Skills (技能) 是以 `SKILL.md` 开头的便携目录，而不是 Java 工具包。在构建 Agent 时，只有每个 Skill 的名称、描述和位置会被添加到系统提示词中。构建器会自动注册一个 `skill_load` 工具。因此，匹配的任务会遵循常规的固定循环：

```text
Skill metadata in system prompt
          |
          v
Model calls skill_load(name, resource?)
          |
          v
Instructions/reference become a Tool result
          |
          v
Next model step follows the loaded guidance
```

这里没有技能路由器或独立的执行引擎。`allowed-tools` 是描述性元数据，从不注册或授予 Java 工具。脚本是普通的技能资源，仅通过明确注册的工具（例如 `bash`）来执行。

## 回合、步骤和终止语义

回合 (Turn) 是一项 Agent 任务：即带有自身 State 的一次 `run`、`runAsync` 或 `runStreamingAsync` 调用。步骤 (Step) 是一次模型调用及其随后的工具批次。因此，`maxSteps` 限制的是模型调用次数。工具调用被单独记录，且每一个工具调用不消耗步骤。

执行状态遵循此状态机：

```text
CREATED -> RUNNING -> COMPLETED
                   -> STOPPED
                   -> FAILED
                   -> CANCELLED
```

模型错误会导致调用失败。工具错误要么成为错误工具消息 (`REPORT_TO_MODEL`)，要么导致调用失败 (`FAIL_FAST`)。达到步骤限制是一个正常的 `STOPPED` 结果，而不是异常。

在将工具结果记录或追加到消息之前，Agent 的 `ToolResultPolicy` 会施加最终的模型上下文界限。默认策略保留开头和结尾在 2000 行/50-KiB 的预览范围内，并添加明确的省略元数据。在截短未被引用的结果之前，它会持久化确切的内容并添加一个 `ToolOutputReference`。生产者级别的分页或流捕获可以附加原始源或临时文件引用；该策略会复用它而不是创建另一个副本。能够产生无限数据的工具仍然必须限制数据获取——上下文策略只是最后一道防线，并不能替代流式处理或分页。

## 并发

公共异步契约使用 `CompletableFuture`。工具批次可以是顺序的或并行的。并行结果始终按照原始调用顺序归约到 AgentState 中，这保持了消息历史的确定性。

Java 8 没有 `CompletableFuture.orTimeout`，因此超时是通过将操作与 `ScheduledExecutorService` 任务进行竞争 (racing) 来实现的。

## 模型提供商边界

线上传输协议 (Wire protocols) 与 Agent 运行时隔离：

```text
Agent Loop
    |
    v
ChatModel / StreamingChatModel       (agent-core)
    |
    v
AbstractHttpChatModel + HttpTransport (agent-model-http)
    |
    +-- OpenAI-compatible Chat Completions
    +-- OpenAI Responses API
    +-- Anthropic Messages API
```

`agent-model-http` 拥有 JSON POST、SSE 成帧 (framing)、取消、超时和 HTTP 错误处理，并打包了内置的协议适配器。每个提供商 Java 包仅拥有请求映射、响应映射和流事件解码。一个新的 HTTP 提供商通常在 `AbstractHttpChatModel` 上实现四个钩子；它不修改 `agent-core`。

规范化的流会报告响应开始、文本增量、工具调用开始、工具参数增量以及使用量 (usage)。它的完成 future 返回与非流式调用相同形状的 `ModelResponse`。

## MCP 边界

MCP 是一种可选的工具源，而不是第二个 Agent 运行时：

```text
Agent Loop -> ToolRegistry -> Tool
                              ^
                              |
                    McpToolAdapter
                              |
                         McpClient
                              |
                     stdio MCP server
```

`agent-mcp` 拥有 2026 无状态发现和每个请求的元数据、传统初始化后备 (fallback)、分页工具发现、JSON-RPC 关联、多轮工具输入、超时以及子进程关闭。一个必需的命名空间将 MCP 名称转换为有效、抗冲突的本地工具名称。然后，Agent 循环将本地和 MCP 工具一视同仁，因此现有的工具拦截器、并行执行、错误策略和输出限制仍然适用。

当前官方的 MCP Java SDK 需要 Java 17 并面向传统的 2025-11-25 协议，因此它无法提供 Java 8/2026 的实现。`McpClient` 仍然足够精简，便于未来在不更改核心或 Agent API 的情况下对接官方 SDK 支持的适配器。请参阅 [MCP client and Tool integration](mcp.md)。

## Agent 执行事件与插件

当配置的模型支持时，`Agent.runStreamingAsync` 会选择 `StreamingChatModel.generateStream`，否则回退到 `ChatModel.generate`。这两条路径会发出相同的 Agent 生命周期事件：

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

每个事件都带有一个单回合唯一的序列号、回合 ID (Turn ID)、Agent 名称以及当前步骤。起始和终止回合事件还带有一个不可变的 State 快照。保留了 `getRunId()` 作为 `getTurnId()` 的别名。

生命周期事件是用于追踪、指标和审计的事实。监听器的失败是隔离的，因此观察者无法更改执行语义。必须包装、重写、拒绝或短路 (short-circuit) 模型/工具调用的操作使用由 `AgentPlugin` 提供的有序 `ModelInterceptor` 和 `ToolInterceptor` 链。拦截器接收不可变的调用数据和 State 快照，而不是实时的、可变的 AgentState。

内置的 `AgentObservability` 插件将这些事实组装为不可变的、与导出器无关的追踪片段：

```text
Trace ID
  Turn span
    Step span
      Model span
      Tool span *
```

它还汇聚了状态、调用、Token、错误和持续时间计数器。内容捕获受到界限限制且默认处于禁用状态。Agent-as-Tool 传播追踪 ID 并将子回合跨度 (child Turn span) 链接到父工具跨度 (parent Tool span)，同时保留独立的状态所有权。核心提供了 OFF、JDK 日志记录、有界的异步 HTTP 平台以及自定义导出器模式，而不需要 OpenTelemetry、Micrometer 或 Spring 依赖。Next.js 控制台使用带版本控制的 JSON 边界，并刻意不作为 Java 运行时的一部分。请参阅 [Agent observability](observability.md)。

取消返回的 Agent future 会通过拦截器 future 传播到活动的模型流或模型 future，然后传播到活动的顺序或并行工具批次。请参阅 [Lifecycle events and plugins](plugins.md) 以获取扩展契约和排序规则。

## Agent 作为工具

Agent 并不直接实现 Tool 接口。`AgentTool` 是一个适配器，因为同一个 Agent 可能会暴露在不同的工具名称/描述下，并且工具调用必须被转换为一个新的 AgentRequest。

```java
Agent main = Agent.builder()
    .tool(researchAgent)
    .build();
```

这个 builder 的重载是 `tool(researchAgent.asTool())` 的简写。调用路径和最大子 agent 深度防止了意外的递归委托循环。
