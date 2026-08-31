package io.github.gitsilence.agent.tool;

import io.github.gitsilence.agent.runtime.Futures;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Base class for a typed Tool whose business logic is asynchronous. */
public abstract class AbstractAsyncTool<I> implements Tool {

    private final ToolDefinition definition;
    private final ToolInputBinding<I> inputBinding;
    private final boolean supportsParallelToolCalls;

    protected AbstractAsyncTool(String name, String description, Class<I> inputType) {
        this(name, description, inputType, false);
    }

    /**
     * 创建强类型异步 Tool，并显式声明其并行安全能力。
     *
     * @param supportsParallelToolCalls 仅当整个异步操作可安全并发时传入 {@code true}
     */
    protected AbstractAsyncTool(String name,
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
    public final CompletableFuture<ToolResult> execute(ToolArguments arguments,
                                                        ToolContext context) {
        try {
            CompletableFuture<ToolResult> result = executeAsync(
                inputBinding.bind(arguments), context
            );
            if (result == null) {
                return Futures.failed(new IllegalStateException(
                    "Tool '" + definition.getName() + "' returned null future"
                ));
            }
            return result;
        } catch (Exception error) {
            return Futures.failed(new ToolExecutionException(
                definition.getName(), error
            ));
        }
    }

    protected abstract CompletableFuture<ToolResult> executeAsync(
        I input, ToolContext context
    ) throws Exception;
}
