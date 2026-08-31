# 多智能体组合

多智能体特性复用了 Agent、Agent-as-Tool、独立的 State 和
`CompletableFuture`。不存在独立的编排运行时。

## 监督者 (Supervisor)

监督者是一个普通的 Agent，它将专家 Agent 注册为 Tool（工具）：

```java
Agent supervisor = Agent.builder()
    .name("supervisor")
    .description("Delegates work to specialists")
    .instructions("Delegate research and coding to the appropriate tools.")
    .model(model)
    .tool(researchAgent)
    .tool(codingAgent)
    .tool(reviewAgent)
    .build();
```

每次专家调用都会创建一个全新的 AgentState。父节点发送一个
`task` 参数，并且只接收子节点的结果以及子节点回合（Turn）的元数据。
`AgentTool` 因此显式声明支持安全并行；监督者配置 `.parallelToolCalls(true)` 后，
同一模型响应中的多个专家调用可以并发执行，结果仍按模型调用顺序回填。

## 独立的并行 Agent

```java
List<AgentResult> results = AgentExecutions.runParallel(Arrays.asList(
    AgentInvocation.of(researchAgent, "Investigate option A"),
    AgentInvocation.of(researchAgent, "Investigate option B"),
    AgentInvocation.of(reviewAgent, "Define review criteria")
)).join();
```

调用会立即开始，结果保持输入时的顺序，出现一次失败就会取消
所有未完成的调用，并且对聚合 future 的取消操作会传播到每一个
调用。不共享任何可变的 State。

## 无需新运行时的模式

- 路由器 (Router)：一个 Agent 在注册的 Agent 工具之间进行选择。
- 审查/辩论 (Review/debate)：调用多个独立的 Agent，然后将它们的输出传递给一个审查者。
- 并行研究 (Parallel research)：使用 `AgentExecutions.runParallel`，然后使用另一个 Agent 综合这些结果。
- 监督者 (Supervisor)：通过 `.tool(agent)` 注册专家。

## 暂缓支持的模式 (Deferred patterns)

交接（Handoff）和群体（Swarm）需要显式的控制权转移结果和一个
AgentRegistry。它们应该作为小型原语来添加，而不是被编码为
普通的工具文本。持久化检查点、分布式调度和图 DSL
仍然在 SDK 的范围之外。
