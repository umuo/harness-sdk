package io.github.gitsilence.agent.tool;

import java.util.concurrent.CompletableFuture;

public interface AsyncToolHandler {

    CompletableFuture<ToolResult> execute(ToolArguments arguments, ToolContext context)
        throws Exception;
}
