package io.github.gitsilence.agent.tool;

import java.util.concurrent.CompletableFuture;

public interface Tool {

    ToolDefinition definition();

    CompletableFuture<ToolResult> execute(ToolArguments arguments, ToolContext context);
}
