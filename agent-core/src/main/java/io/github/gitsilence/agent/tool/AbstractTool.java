package io.github.gitsilence.agent.tool;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for a synchronous typed Tool.
 *
 * <p>The input POJO supplies the JSON Schema through its fields and
 * {@link io.github.gitsilence.agent.tool.annotation.ToolParam} annotations.
 * Implementations only provide tool metadata and business logic.</p>
 */
public abstract class AbstractTool<I> implements Tool {

    private final ToolDefinition definition;
    private final ToolInputBinding<I> inputBinding;
    private final boolean supportsParallelToolCalls;

    protected AbstractTool(String name, String description, Class<I> inputType) {
        this(name, description, inputType, false);
    }

    /**
     * 创建强类型同步 Tool，并显式声明其并行安全能力。
     *
     * @param supportsParallelToolCalls 仅当实现可安全并发执行时传入 {@code true}
     */
    protected AbstractTool(String name,
                           String description,
                           Class<I> inputType,
                           boolean supportsParallelToolCalls) {
        this.inputBinding = ToolInputBinding.create(
            Objects.requireNonNull(inputType, "inputType")
        );
        this.definition = ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(inputBinding.schema())
            .build();
        this.supportsParallelToolCalls = supportsParallelToolCalls;
    }

    @Override
    public final ToolDefinition definition() {
        return definition;
    }

    @Override
    public final boolean supportsParallelToolCalls() {
        return supportsParallelToolCalls;
    }

    @Override
    public final CompletableFuture<ToolResult> execute(
            final ToolArguments arguments,
            final ToolContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ToolResult result = execute(inputBinding.bind(arguments), context);
                if (result == null) {
                    throw new IllegalStateException(
                        "Tool '" + definition.getName() + "' returned null"
                    );
                }
                return result;
            } catch (Exception error) {
                throw new ToolExecutionException(definition.getName(), error);
            }
        }, context.getExecutor());
    }

    protected abstract ToolResult execute(I input, ToolContext context) throws Exception;
}
