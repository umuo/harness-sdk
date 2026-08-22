package io.github.gitsilence.agent.tool;

public interface SyncToolHandler {

    ToolResult execute(ToolArguments arguments, ToolContext context) throws Exception;
}
