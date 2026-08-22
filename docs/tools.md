# Tool Authoring

The Tool API has one asynchronous runtime contract and three authoring levels:

1. Extend `AbstractTool<I>` for ordinary blocking or CPU-bound logic.
2. Extend `AbstractAsyncTool<I>` when the operation already returns a
   `CompletableFuture<ToolResult>`.
3. Use `@Tool` methods for compact groups of application functions.

`Tool`, `ToolDefinition`, `Tools.sync` and `Tools.async` remain the low-level
escape hatch. Most application code should not construct JSON Schema strings.

## Typed base class

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

The input class must have a no-argument constructor and mutable instance
fields. Top-level fields may be public or private; static, transient and
synthetic fields are ignored. Nested POJOs must be Jackson-deserializable.
Optional JSON arguments leave a field initializer intact, which
makes ordinary Java defaults useful. `final` input fields are rejected when the
Tool is constructed.

`@ToolParam` can rename a field for the LLM, describe it, and mark it optional.
An unannotated field is required. `Optional<T>` fields are always optional and
receive `Optional.empty()` when omitted.

The first schema generator supports strings, booleans, integral and decimal
numbers, enums, arrays, collections, maps and simple nested POJOs. Generic
collection item types are retained. Unknown top-level properties are rejected
both by the generated schema and the runtime binder.
Runtime validation remains authoritative; domain rules such as a changing
maximum timeout belong in the Tool implementation.

## Asynchronous base class

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

`executeAsync` must return a non-null future and should not block before doing
so. Cancellation and exceptions remain visible to the Agent runtime.

## Annotation-based Tools

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

The method schema is derived from Java parameter types. `ToolContext` and
`ToolArguments` are injectable runtime parameters and are not sent to the LLM.
`Optional<T>` is optional in the schema. Ordinary values are serialized to the
Tool result, `String` is returned as-is, `ToolResult` preserves structured
metadata, and `CompletionStage` values are flattened asynchronously.

Method parameter names require the Java compiler `-parameters` option. This
repository enables it in the parent POM. Libraries that cannot guarantee that
compiler setting should declare `@ToolParam(name = "...")` explicitly.

Annotated Tool names must be unique within one scanned object. Invalid
definitions fail immediately during Agent construction rather than on the
first model call.

## Why the layers are separate

The high-level APIs cover stable, common Java cases. The low-level contract is
kept for dynamic Tools and specialized JSON Schema constraints, so the typed
API does not grow a large annotation DSL.

The annotation approach was informed by LangChain4j's
[`@Tool`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/Tool.java),
[`@P`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/P.java),
and
[`ToolSpecifications`](https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/ToolSpecifications.java)
APIs. This SDK keeps its own smaller execution contract, Java 8 async model and
State-driven Agent Loop.
