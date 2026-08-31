package io.github.gitsilence.agent.agent;

import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolArguments;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 把一个 Agent 适配成普通 Tool，供父 Agent 委托任务。
 *
 * <p>模型只向子 Agent 传递 {@code task} 文本。子 Agent 会获得全新的 State；父级只
 * 接收最终 ToolResult 和必要的追踪关联信息，不共享消息、变量或 Todo。</p>
 */
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
    public boolean supportsParallelToolCalls() {
        // 每次委派都会创建独立 State；显式开启批次并行时可安全地并发运行子 Turn。
        return true;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolArguments arguments,
                                                 ToolContext context) {
        // parent* 字段只用于追踪谱系，不会把父 State 注入子 Agent。
        AgentRequest.Builder request = AgentRequest.builder()
            .input(arguments.requireString("task"))
            .metadata("parentRunId", context.getRunId())
            .metadata("parentTurnId", context.getTurnId())
            .metadata("parentToolCallId", context.getToolCallId());
        Map<String, Object> parentMetadata = context.getMetadata();
        Object traceId = parentMetadata.get("traceId");
        if (traceId instanceof String
                && !((String) traceId).trim().isEmpty()) {
            request.metadata("traceId", traceId);
        }

        // 必须复用当前 Runner，才能应用统一的递归检测、深度限制和取消传播。
        CompletableFuture<AgentResult> child = context.getRunner()
            .runChildAsync(
                delegate, request.build(), context.getInvocationPath()
            );
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
                // 子 Agent 正常 STOPPED 会成为错误 ToolResult，让父模型决定如何恢复。
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
                // 父工具调用取消时，继续运行子 Turn 已无意义。
                child.cancel(true);
            }
        });
        return result;
    }
}
