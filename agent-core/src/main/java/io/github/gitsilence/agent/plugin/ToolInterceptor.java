package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.tool.ToolResult;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ToolInterceptor {

    CompletableFuture<ToolResult> intercept(
        ToolInvocation invocation,
        ToolChain chain
    );
}
