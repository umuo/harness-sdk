package io.github.gitsilence.agent.tool;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 创建低级动态 Tool 的工厂。 */
public final class Tools {

    private Tools() {
    }

    public static Tool sync(final ToolDefinition definition,
                            final SyncToolHandler handler) {
        return sync(definition, false, handler);
    }

    /**
     * 创建同步 Tool，并显式声明是否支持同一批次内并行执行。
     *
     * @param supportsParallelToolCalls 只有无共享副作用且线程安全时才设为 {@code true}
     */
    public static Tool sync(final ToolDefinition definition,
                            final boolean supportsParallelToolCalls,
                            final SyncToolHandler handler) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public boolean supportsParallelToolCalls() {
                return supportsParallelToolCalls;
            }

            @Override
            public CompletableFuture<ToolResult> execute(final ToolArguments arguments,
                                                         final ToolContext context) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return handler.execute(arguments, context);
                    } catch (Exception e) {
                        throw new ToolExecutionException(definition.getName(), e);
                    }
                }, context.getExecutor());
            }
        };
    }

    public static Tool async(final ToolDefinition definition,
                             final AsyncToolHandler handler) {
        return async(definition, false, handler);
    }

    /**
     * 创建异步 Tool，并显式声明是否支持同一批次内并行执行。
     *
     * @param supportsParallelToolCalls 只有整个异步生命周期均可安全并发时才设为
     *                                  {@code true}
     */
    public static Tool async(final ToolDefinition definition,
                             final boolean supportsParallelToolCalls,
                             final AsyncToolHandler handler) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public boolean supportsParallelToolCalls() {
                return supportsParallelToolCalls;
            }

            @Override
            public CompletableFuture<ToolResult> execute(ToolArguments arguments,
                                                         ToolContext context) {
                try {
                    CompletableFuture<ToolResult> future = handler.execute(arguments, context);
                    if (future == null) {
                        throw new IllegalStateException("Async tool returned null future");
                    }
                    return future;
                } catch (Exception e) {
                    CompletableFuture<ToolResult> failed = new CompletableFuture<ToolResult>();
                    failed.completeExceptionally(new ToolExecutionException(
                        definition.getName(), e
                    ));
                    return failed;
                }
            }
        };
    }
}
