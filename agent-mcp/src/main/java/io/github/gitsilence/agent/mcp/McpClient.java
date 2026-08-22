package io.github.gitsilence.agent.mcp;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Provider-neutral MCP client boundary. Implementations own one MCP session.
 * The first operation may initialize the session lazily.
 */
public interface McpClient extends AutoCloseable {

    CompletableFuture<McpInitializeResult> initialize();

    CompletableFuture<List<McpToolDefinition>> listTools();

    CompletableFuture<McpCallToolResult> callTool(
        String toolName,
        String argumentsJson
    );

    Optional<McpInitializeResult> getInitializeResult();

    boolean isOpen();

    @Override
    void close();
}
