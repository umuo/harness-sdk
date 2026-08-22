package io.github.gitsilence.agent.agent;

import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolArguments;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolResult;

import java.util.concurrent.CompletableFuture;

public final class AgentTool implements Tool {

    private static final String INPUT_SCHEMA =
        "{\"type\":\"object\",\"properties\":{"
            + "\"task\":{\"type\":\"string\","
            + "\"description\":\"Task delegated to this agent\"}},"
            + "\"required\":[\"task\"]}";

    private final Agent delegate;
    private final ToolDefinition definition;

    public AgentTool(Agent delegate, String name, String description) {
        this.delegate = delegate;
        this.definition = ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(INPUT_SCHEMA)
            .metadata("kind", "agent")
            .build();
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolArguments arguments,
                                                 ToolContext context) {
        AgentRequest request = AgentRequest.builder()
            .input(arguments.requireString("task"))
            .metadata("parentRunId", context.getRunId())
            .build();

        return context.getRunner()
            .runChildAsync(delegate, request, context.getInvocationPath())
            .thenApply(result -> {
                ToolResult toolResult = result.isCompleted()
                    ? ToolResult.success(result.getOutput())
                    : ToolResult.failure(
                        "Child agent stopped: " + result.getStopReason()
                    );
                return toolResult
                    .withMetadata("childRunId", result.getState().getRunId())
                    .withMetadata("childStatus", result.getStatus().name());
            });
    }
}
