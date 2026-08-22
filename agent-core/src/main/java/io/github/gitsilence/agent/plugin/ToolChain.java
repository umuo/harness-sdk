package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.tool.ToolResult;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ToolChain {

    CompletableFuture<ToolResult> proceed(ToolInvocation invocation);
}
