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
            .metadata("parentTurnId", context.getTurnId())
            .build();

        CompletableFuture<AgentResult> child = context.getRunner()
            .runChildAsync(delegate, request, context.getInvocationPath());
        CompletableFuture<ToolResult> result = new CompletableFuture<ToolResult>();
        child.whenComplete((agentResult, error) -> {
            if (result.isCancelled()) {
                return;
            }
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            try {
                ToolResult toolResult = agentResult.isCompleted()
                    ? ToolResult.success(agentResult.getOutput())
                    : ToolResult.failure(
                        "Child agent stopped: " + agentResult.getStopReason()
                    );
                result.complete(toolResult
                    .withMetadata("childRunId", agentResult.getState().getRunId())
                    .withMetadata("childTurnId", agentResult.getState().getTurnId())
                    .withMetadata("childStatus", agentResult.getStatus().name()));
            } catch (Throwable mappingError) {
                result.completeExceptionally(mappingError);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                child.cancel(true);
            }
        });
        return result;
    }
}
