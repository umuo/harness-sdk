# 工具编写

工具 API 拥有一个异步运行时契约以及三个编写层级：

1. 继承 `AbstractTool<I>` 用于普通的阻塞或 CPU 密集型逻辑。
2. 继承 `AbstractAsyncTool<I>` 当操作已经返回一个 `CompletableFuture<ToolResult>` 时。
3. 使用 `@Tool` 方法用于紧凑的应用程序功能组。

`Tool`、`ToolDefinition`、`Tools.sync` 和 `Tools.async` 仍然是底层的逃生舱（escape hatch）。大多数应用代码不应该去构造 JSON Schema 字符串。

## 类型化基类

```java
public final class WeatherTool
        extends AbstractTool<WeatherTool.Input> {

    private final WeatherClient client;

    public WeatherTool(WeatherClient client) {
        super("weather", "Gets current weather for a city", Input.class);
        this.client = client;
    }

    @Override
    protected ToolResult execute(Input input, ToolContext context) {
        return ToolResult.success(client.current(input.city, input.units));
    }

    public static final class Input {
        @ToolParam(description = "City name")
        public String city;

        @ToolParam(description = "metric or imperial", required = false)
        public String units = "metric";
    }
}
```

输入类必须有一个无参构造函数和可变的实例字段。顶级字段可以是公开（public）或私有（private）的；静态（static）、瞬态（transient）和合成（synthetic）字段将被忽略。嵌套的 POJO 必须可以通过 Jackson 进行反序列化。可选的 JSON 参数会保持字段的初始值不变，这使得普通的 Java 默认值变得非常有用。当构建 Tool 时，如果存在 `final` 输入字段将会被拒绝。

`@ToolParam` 可以为 LLM 重命名字段、提供描述，并将其标记为可选。未加注解的字段为必填项。`Optional<T>` 字段始终是可选的，并在省略时接收 `Optional.empty()`。

第一版 schema 生成器支持字符串、布尔值、整数和小数、枚举、数组、集合、映射（maps）以及简单的嵌套 POJO。泛型集合项的类型会被保留。未知的顶级属性将被生成的 schema 和运行时绑定器（runtime binder）同时拒绝。运行时验证仍然是权威的；例如不断变化的最大超时时间等领域规则应当属于 Tool 的实现范畴。

## 异步基类

```java
public final class LookupTool
        extends AbstractAsyncTool<LookupTool.Input> {

    public LookupTool() {
        super("lookup", "Looks up one record", Input.class);
    }

    @Override
    protected CompletableFuture<ToolResult> executeAsync(
            Input input,
            ToolContext context) {
        return client.lookup(input.id)
            .thenApply(value -> ToolResult.success(value));
    }

    public static final class Input {
        public String id;
    }
}
```

`executeAsync` 必须返回一个非空的 future，并且在返回之前不应当阻塞。取消操作和异常在 Agent 运行时中仍然是可见的。

## 基于注解的 Tools

```java
public final class MathTools {

    @Tool("Multiplies two integers")
    public CompletableFuture<Long> multiply(
            @ToolParam("First integer") long a,
            @ToolParam("Second integer") long b,
            ToolContext context) {
        return CompletableFuture.completedFuture(a * b);
    }
}

Agent agent = Agent.builder()
    .toolsFrom(new MathTools())
    .build();
```

方法 schema 由 Java 参数类型派生。`ToolContext` 和 `ToolArguments` 是可注入的运行时参数，不会被发送给 LLM。`Optional<T>` 在 schema 中是可选的。普通的值会被序列化到 Tool 结果中，`String` 保持原样返回，`ToolResult` 保留了结构化的元数据，而 `CompletionStage` 的值会被异步展平。

方法参数名需要 Java 编译器的 `-parameters` 选项。本代码库在父 POM 中启用了该选项。如果库无法保证该编译器设置，则应显式地声明 `@ToolParam(name = "...")`。

被注解的 Tool 名称在一个被扫描的对象内必须是唯一的。无效的定义会在 Agent 构建期间立即抛出失败，而不是在第一次模型调用时。

## 为什么层级是分离的

高级 API 覆盖了稳定且常见的 Java 场景。低级契约被保留用于动态 Tools 和特殊的 JSON Schema 约束，因此类型化 API 不会增长出一个庞大的注解 DSL。

注解的方法受到了 LangChain4j 的
[`@Tool`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/Tool.java)、
[`@P`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/P.java)
和
[`ToolSpecifications`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/ToolSpecifications.java)
API 的启发。本 SDK 保持了其自身较小的执行契约、Java 8 异步模型以及状态驱动的 Agent 循环（State-driven Agent Loop）。
