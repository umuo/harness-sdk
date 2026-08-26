package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.tool.ToolResult;

import java.util.concurrent.CompletableFuture;

/** 模型工具调用的有序拦截器，可包装、改写、拒绝或短路一次 Tool 执行。 */
@FunctionalInterface
public interface ToolInterceptor {

    CompletableFuture<ToolResult> intercept(
        ToolInvocation invocation,
        ToolChain chain
    );
}
