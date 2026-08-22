package io.github.gitsilence.agent.tool;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class Tools {

    private Tools() {
    }

    public static Tool sync(final ToolDefinition definition,
                            final SyncToolHandler handler) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
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
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(handler, "handler");
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
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
