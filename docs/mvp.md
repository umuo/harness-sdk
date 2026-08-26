# MVP 范围与路线图

## 包含在 0.1 版本中

- 提供商无关的完整响应和流式模型 API。
- 固定的模型/工具智能体循环 (Agent Loop)。
- 每次调用的智能体状态 (AgentState) 和不可变的快照。
- 编程式的同步和异步工具。
- 基于注解的轻量级工具。
- 不可变的工具注册表 (ToolRegistry) 和确定性的工具执行器 (ToolExecutor)。
- 顺序和并行的工具批处理。
- 最大步数、自定义终止条件和错误策略。
- 作为工具的智能体 (Agent-as-Tool)，包含子状态隔离和递归保护。
- 基于文件的智能体技能 (Agent Skills)，具有标准的 `SKILL.md` 元数据、递归发现以及渐进式指令/资源加载。
- 有状态的、基于动作的 Todo 工具，作用域限定于单个智能体轮次 (Agent Turn)。
- Java 8 JSON HTTP 和 SSE 传输抽象。
- 兼容 OpenAI 的流式和非流式 Chat Completions 提供商。
- 流式和非流式 OpenAI Responses API 提供商。
- 流式和非流式 Anthropic Messages API 提供商。
- 通过 `runStreamingAsync` 提供智能体生命周期和模型增量事件。
- 构建时插件，包含生命周期观察者和有序的模型/工具拦截器。
- 导出器无关的轮次/步骤/模型/工具 (Turn/Step/Model/Tool) 追踪、累积执行指标、有界可选内容捕获，以及父/子智能体追踪关联。
- 从智能体执行到模型和工具操作的取消传播。
- 轻量级的有序并行智能体组合。
- 工作区作用域的 `read_file`、`write_file`、`edit`、`apply_patch`、`glob` 以及可选的 `bash` 工具。
- 可恢复的超大工具输出，通过操作系统临时快照和源引用实现，无需递归溢出复制。
- MCP 2026-07-28 无状态标准输入输出 (stdio) 客户端，自动回退到 2025-11-25 版本，支持分页和缓存感知的工具发现、多轮往返的工具输入、结构化错误以及智能体工具适配。
- 可运行的示例和基于脚本化模型的单元测试。

## 刻意排除

- 图、边或工作流 DSL。
- 检查点机制和状态持久化。
- 持久化事件总线或可回放的事件存储。
- 动态插件加载、热卸载、依赖注入或服务容器语义。
- RAG 和向量数据库。
- MCP 流式 HTTP、旧版 HTTP+SSE、资源 (resources)、提示词 (prompts)、订阅 (subscriptions)、一等根/采样/启发式 API (roots/sampling/elicitation APIs)、任务扩展和服务器模式。
- 包含人在回路 (Human-in-the-loop) 的运行时。
- 基于语义或规则的技能路由器；由模型根据描述进行选择。
- 分布式执行。
- Spring 集成。

## 可能的后续工作

1. 更多提供商模块。
2. OpenTelemetry、Micrometer 和结构化日志导出器适配器。
3. 显式的交接 (Handoff) 控制信号以及智能体注册表 (AgentRegistry)。
4. 在 AgentState 之外实现的可选持久化。

主管 (Supervisor)、路由器、并行智能体和审查/辩论模式应当保持为 Agent、AgentTool 和 CompletableFuture 的组合。交接 (Handoff) 和群体 (swarm) 需要显式的控制权转移概念，不应该隐藏在正常的工具返回语义中。
