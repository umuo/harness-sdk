package io.github.gitsilence.agent.mcp;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Provider-neutral MCP client boundary. Implementations prepare protocol state
 * lazily; for stateless MCP, initialize() performs discovery rather than the
 * removed wire-level initialize handshake.
 */
public interface McpClient extends AutoCloseable {

    CompletableFuture<McpInitializeResult> initialize();

    CompletableFuture<List<McpToolDefinition>> listTools();

    default CompletableFuture<McpToolCatalog> listToolCatalog() {
        return listTools().thenApply(McpToolCatalog::uncached);
    }

    CompletableFuture<McpCallToolResult> callTool(
        String toolName,
        String argumentsJson
    );

    Optional<McpInitializeResult> getInitializeResult();

    boolean isOpen();

    @Override
    void close();
}
