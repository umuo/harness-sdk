package io.github.gitsilence.agent.tool;

import java.util.concurrent.CompletableFuture;

public interface Tool {

    ToolDefinition definition();

    /**
     * 当前 Tool 是否允许与同一模型响应中的其他并行 Tool Call 同时执行。
     *
     * <p>默认返回 {@code false}，因为运行时无法从 Schema 推断实现是否会修改共享状态、
     * 文件或外部系统。只有实现自身能够证明线程安全且不存在顺序依赖时才应返回
     * {@code true}。</p>
     */
    default boolean supportsParallelToolCalls() {
        return false;
    }

    CompletableFuture<ToolResult> execute(ToolArguments arguments, ToolContext context);
}
