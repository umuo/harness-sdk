package io.github.gitsilence.agent.mcp;

import io.github.gitsilence.agent.runtime.Futures;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolArguments;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
import io.github.gitsilence.agent.tool.ToolOutputReference;
import io.github.gitsilence.agent.tool.ToolOutputStore;
import io.github.gitsilence.agent.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class McpToolAdapter implements Tool {

    private final McpClient client;
    private final McpToolDefinition remote;
    private final ToolOutputStore outputStore;
    private final ToolDefinition definition;

    McpToolAdapter(McpClient client,
                   McpInitializeResult server,
                   McpToolDefinition remote,
                   String namespace,
                   ToolOutputStore outputStore) {
        this.client = Objects.requireNonNull(client, "client");
        this.remote = Objects.requireNonNull(remote, "remote");
        this.outputStore = Objects.requireNonNull(outputStore, "outputStore");
        String localName = McpToolNames.localName(namespace, remote.getName());
        String description = remote.getDescription().trim();
        if (description.isEmpty()) {
            description = "MCP Tool '" + remote.getName() + "' provided by '"
                + server.getServerInfo().getName() + "'";
        }
        this.definition = ToolDefinition.builder()
            .name(localName)
            .description(description)
            .inputSchema(remote.getInputSchema())
            .metadata("protocol", "mcp")
            .metadata("mcpNamespace", namespace)
            .metadata("mcpServer", server.getServerInfo().getName())
            .metadata("mcpRemoteTool", remote.getName())
            .metadata("mcpOutputSchema", remote.getOutputSchema())
            .build();
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolArguments arguments,
                                                  ToolContext context) {
        final CompletableFuture<ToolResult> output =
            new CompletableFuture<ToolResult>();
        CompletableFuture<McpCallToolResult> invocation = client.callTool(
            remote.getName(), arguments.rawJson()
        );
        invocation
            .whenComplete((result, error) -> {
                if (error != null) {
                    output.completeExceptionally(asToolFailure(error));
                    return;
                }
                try {
                    output.complete(toToolResult(result));
                } catch (Throwable conversionError) {
                    output.completeExceptionally(conversionError);
                }
            });
        output.whenComplete((ignored, error) -> {
            if (output.isCancelled()) {
                invocation.cancel(true);
            }
        });
        return output;
    }

    private ToolResult toToolResult(McpCallToolResult remoteResult)
            throws IOException {
        ToolResult result;
        if (remoteResult.isError()) {
            ToolErrorInfo errorInfo = ToolErrorInfo.builder(
                "MCP_TOOL_ERROR",
                summary(remoteResult.getModelContent())
            ).retryable(true)
                .recoveryHint(
                    "Use the remote error details to correct the arguments or choose another Tool."
                )
                .detail("mcpServer", definition.getMetadata().get("mcpServer"))
                .detail("mcpTool", remote.getName())
                .build();
            result = ToolResult.failure(remoteResult.getModelContent(), errorInfo);
        } else {
            result = ToolResult.success(remoteResult.getModelContent());
        }
        result = result
            .withMetadata("mcpServer", definition.getMetadata().get("mcpServer"))
            .withMetadata("mcpRemoteTool", remote.getName())
            .withMetadata(
                "mcpHasStructuredContent",
                !remoteResult.getStructuredContentJson().isEmpty()
            );

        if (remoteResult.isOmittedFromModelContent()) {
            Path path = outputStore.writeUtf8(
                "mcp-" + definition.getName() + "-",
                remoteResult.getRawResultJson()
            );
            String content = result.getContent()
                + "\n\n[exact MCP result saved at " + path + "]";
            result = result.withContent(content)
                .withOutputReference(ToolOutputReference.temporaryFile(
                    path,
                    "exact MCP tools/call result, including omitted binary or unknown content"
                ))
                .withMetadata("mcpResultPreservation", "temporary_file")
                .withMetadata("mcpResultFullPath", path.toString());
        }
        return result;
    }

    private ToolFailureException asToolFailure(Throwable failure) {
        Throwable error = Futures.unwrap(failure);
        McpClientException mcp = find(error, McpClientException.class);
        String code = mcp == null ? "MCP_CALL_FAILED" : mcp.getCode();
        String message = error.getMessage() == null
            ? "MCP Tool call failed" : error.getMessage();
        ToolErrorInfo.Builder info = ToolErrorInfo.builder(code, summary(message))
            .retryable(mcp != null && mcp.isRetryable())
            .recoveryHint(
                "Check the MCP server process and error details, then retry only if appropriate."
            )
            .detail("mcpServer", definition.getMetadata().get("mcpServer"))
            .detail("mcpTool", remote.getName());
        if (mcp != null && mcp.getRpcCode() != null) {
            info.detail("jsonRpcCode", mcp.getRpcCode());
        }
        if (mcp != null && mcp.getRpcData() != null
                && !mcp.getRpcData().isEmpty()) {
            info.detail("jsonRpcData", summary(mcp.getRpcData()));
        }
        return new ToolFailureException(info.build(), error);
    }

    private static String summary(String value) {
        String normalized = value == null || value.trim().isEmpty()
            ? "MCP Tool call failed without an error message" : value.trim();
        return normalized.length() <= 512
            ? normalized : normalized.substring(0, 509) + "...";
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }
}
