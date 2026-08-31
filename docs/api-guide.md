# API 指南

## 通过继承定义类型化 Tool

```java
public final class EchoTool extends AbstractTool<EchoTool.Input> {

    public EchoTool() {
        super("echo", "Returns the supplied text", Input.class);
    }

    @Override
    protected ToolResult execute(Input input, ToolContext context) {
        return ToolResult.success(input.text);
    }

    public static final class Input {
        @ToolParam(description = "Text to return")
        public String text;

        @ToolParam(
            name = "uppercase",
            description = "Whether to convert the text to upper case",
            required = false
        )
        public Boolean uppercase;
    }
}
```

SDK 会生成对象的 JSON Schema 并将参数绑定到 `Input`。
同步逻辑在 `AgentRunner` worker 执行器上运行。当操作已经返回
`CompletableFuture<ToolResult>` 时，请扩展
`AbstractAsyncTool<I>` 并实现 `executeAsync`。

## 定义带有注解的 Tool

```java
public final class MathTools {

    @Tool(name = "add", description = "Adds two integers")
    public int add(
            @ToolParam(description = "First integer") int a,
            @ToolParam(description = "Second integer") int b,
            ToolContext context) {
        return a + b;
    }
}
```

使用 `AgentBuilder.toolsFrom(new MathTools())` 注册所有带有注解的方法。
在 Java 8 上，除非编译时使用 `-parameters`，否则无法可靠地获取参数名称；
因此建议显式使用 `@ToolParam(name = ...)`。
`ToolContext` 和 `ToolArguments` 方法参数由运行时注入，并从 schema 中省略。
方法可以返回 `String`、可序列化的对象、`ToolResult`，或相应的 `CompletableFuture`/`CompletionStage`。

## 使用底层逃生舱 (escape hatch)

对于不常见的特定提供商的 schema 关键字或动态定义，仍然可以使用手动定义 schema 的方式：

```java
Tool echo = Tools.sync(
    ToolDefinition.builder()
        .name("echo")
        .description("Returns the supplied text")
        .inputSchema("{\"type\":\"object\",\"properties\":{" +
            "\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}")
        .build(),
    (arguments, context) ->
        ToolResult.success(arguments.requireString("text"))
);
```

这是基础设施 API，不推荐作为应用 Tool 的默认方式。
有关类型支持和绑定规则，请参阅 [Tool 编写](tools.md) (Tool authoring)。

## 构建并运行 Agent

```java
Agent agent = Agent.builder()
    .name("assistant")
    .description("A general assistant")
    .instructions("Answer clearly and use tools when useful.")
    .model(chatModel)
    .tool(echo)
    .maxSteps(10)
    .build();

AgentResult result = agent.run(AgentRequest.of("Echo hello"));
System.out.println(result.getOutput());
```

每个轮次 (Turn) 都会创建一个新的 AgentState。可以通过
`AgentRequest.Builder.initialMessages(...)` 提供对话历史记录；SDK 不会持久化历史记录。

## 委托给另一个 Agent

```java
Agent researcher = Agent.builder()
    .name("research")
    .description("Researches a delegated topic")
    .instructions("Return a concise research report.")
    .model(chatModel)
    .tool(searchTool)
    .build();

Agent supervisor = Agent.builder()
    .name("supervisor")
    .description("Delegates specialist work")
    .instructions("Delegate research work to the research tool.")
    .model(chatModel)
    .tool(researcher)
    .build();
```

子 Agent 只接收父模型生成的 `task`。它不会继承父 Agent 的消息、变量、元数据或待办事项 (todos)。

## Agent 技能 (Skills)

Agent 技能 (Skill) 是一个包含 `SKILL.md` 文件的目录。您可以注册单个技能，或递归发现目录中的技能：

```java
Agent reviewer = Agent.builder()
    .name("reviewer")
    .description("Reviews code")
    .model(chatModel)
    .skill(Paths.get(".agents/skills/code-review"))
    // Or: .skillsFrom(Paths.get(".agents/skills"))
    .build();
```

初始系统提示 (system prompt) 仅包含技能的名称、描述和位置。
当某个技能相关时，模型会调用自动注册的 `skill_load` Tool。
省略 `resource` 将加载 `SKILL.md` 的 Markdown 主体内容；
提供相对资源路径则会从技能根目录内部加载该 UTF-8 文本文件。
因此，技能指令和参考资料仅在激活后才会占用上下文。

如果发现任何无效的 `SKILL.md`，`skillsFrom` 会快速失败 (fails fast)。
希望进行部分发现的应用程序可以检查 `SkillLoader.discover(path)` 并决定如何处理其诊断信息。
有关文件格式、边界和安全行为，请参阅 [Agent 技能](skills.md) (Agent Skills)。

## 待办事项 (Todos)

待办事项 (Todos) 由作用域为当前轮次 (Turn) 的一个有状态 Tool 管理：

```java
Agent agent = Agent.builder()
    .tool(TodoTool.create())
    .build();
```

模型通过指定 `action` 为 `LIST`、`ADD`、`UPDATE`、`COMPLETE` 或 `CLEAR` 来调用 `todo`。
将这些操作放在一个 Tool 之后，可以减少模型的工具选择面 (tool selection surface)。
生成的待办事项状态保留在 `AgentState` 中，并包含在不可变的结果快照中。

## 异步执行

```java
CompletableFuture<AgentResult> future = agent.runAsync(request);
```

`.parallelToolCalls(true)` 会启用安全并行调度，但 Tool 默认仍是独占的。只有通过
`supportsParallelToolCalls()`、强类型基类的并行构造参数、`Tools.sync/async` 的
并行重载，或 `@Tool(parallel = true)` 显式声明并行安全的 Tool 才会同时执行。
独占 Tool 会等待前面的并行组完成，并阻止后续调用提前开始。即使在并行模式下，
结果也会按照模型原始调用工具的顺序追加到消息中。

每个 `ToolExecutionRecord` 会保留调度、实际开始和完成时间。可以通过
`getDispatchDurationNanos()` 判断调用是否主要在等待前序独占/并行阶段，通过
`getHandlerDurationNanos()` 查看拦截器与 Tool 的实际处理耗时，二者共同构成
`getTotalDurationNanos()`。

每个结果在进入状态 (State) 或模型历史记录之前，都会经过最终的 `ToolResultPolicy` 处理。
默认的 `BoundedToolResultPolicy` 将输出限制为 50 KiB 和 2,000 行，并提供头部/尾部预览。
在截断未引用的结果之前，它会将精确的 UTF-8 内容保存在操作系统临时目录下。
如果 Tool 的原始来源或生产者级别的捕获已经是可读的，Tool 可以附加一个 `ToolOutputReference`，从而防止重复生成快照。
应用程序可以替换这些限制和输出目录：

```java
Path outputDirectory = Paths.get("/secure/runtime/agent-output");

Agent agent = Agent.builder()
    .name("assistant")
    .model(chatModel)
    .toolResultPolicy(new BoundedToolResultPolicy(
        32 * 1024, 1000, outputDirectory
    ))
    .build();
```

当模型需要稳定的错误代码和显式的恢复指令时，Tools 可以抛出带有 `ToolErrorInfo` 的
`ToolFailureException`。请参阅 [Tool 结果、保留与错误](tool-results.md)。

## 流式传输模型响应

在模型 (Model) 层，流式传输是与提供商无关的 (provider-neutral)：

```java
StreamingChatModel model = OpenAiResponsesChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .model(System.getenv("LLM_MODEL"))
    .build();

ModelStream stream = model.generateStream(request, event -> {
    switch (event.getType()) {
        case TEXT_DELTA:
            System.out.print(event.getDelta());
            break;
        case TOOL_ARGUMENTS_DELTA:
            System.out.print(event.getDelta());
            break;
        default:
            break;
    }
});

CompletableFuture<ModelResponse> completed = stream.completion();
```

调用 `stream.cancel()` 会取消其完成的 future 并断开底层 HTTP 流的连接。
有关提供商配置和扩展点，请参阅 [模型提供商与流式传输](model-providers.md)。

## 流式传输 Agent 执行

使用 `runStreamingAsync` 可以同时观察模型增量 (deltas) 和固定的 Agent 循环：

```java
CompletableFuture<AgentResult> execution = agent.runStreamingAsync(
    AgentRequest.of("Research Java 8 concurrency"),
    event -> {
        if (event.getType() == AgentEventType.MODEL_STREAM_EVENT
                && event.getModelStreamEvent().getType()
                    == ModelStreamEventType.TEXT_DELTA) {
            System.out.print(event.getModelStreamEvent().getDelta());
        }
        if (event.getType() == AgentEventType.TOOL_COMPLETED) {
            System.out.println(event.getToolExecution().getResult().getContent());
        }
    }
);
```

事件序列还包括轮次/步骤 (Turn/Step) 开始、完整的模型响应、工具开始/完成、步骤完成以及一个最终的轮次事件。调用 `execution.cancel(true)` 可取消活动的模型或工具操作。

## 使用 Plugin 扩展生命周期行为

使用 `AgentPlugin` 可以将只读事件观察器和有序的模型/工具拦截器绑定在一起：

```java
AgentPlugin audit = new AgentPlugin() {
    @Override
    public String name() {
        return "audit";
    }

    @Override
    public void onEvent(AgentEvent event) {
        auditLog(event.getTurnId(), event.getStep(), event.getType());
    }

    @Override
    public List<ToolInterceptor> toolInterceptors() {
        return Collections.singletonList((invocation, chain) -> {
            long started = System.nanoTime();
            return chain.proceed(invocation).thenApply(result ->
                result.withMetadata(
                    "elapsedNanos", System.nanoTime() - started
                )
            );
        });
    }
};

Agent agent = Agent.builder()
    .name("assistant")
    .model(chatModel)
    .plugin(audit)
    .build();
```

观察器无法控制执行，且它们的异常是隔离的。拦截器可以调用
`chain.proceed(...)` 来包装或重写调用，或者返回它们自己的 future 以实现短路 (short-circuit)。
请参阅 [生命周期事件与插件](plugins.md)。

## 并行运行独立的 Agent

并行协作是一个小型的 `CompletableFuture` 组合：

```java
CompletableFuture<List<AgentResult>> reports =
    AgentExecutions.runParallel(Arrays.asList(
        AgentInvocation.of(researchAgent, "Research the topic"),
        AgentInvocation.of(reviewAgent, "Review the proposal")
    ));
```

所有调用都有独立的状态 (State)。结果保留调用的顺序；一个失败将取消未完成的兄弟调用。此辅助工具不添加节点 (nodes)、边缘 (edges) 或工作流运行时。
