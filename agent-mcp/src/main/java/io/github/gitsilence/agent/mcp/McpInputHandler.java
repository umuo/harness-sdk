package io.github.gitsilence.agent.mcp;

import java.util.concurrent.CompletableFuture;

/**
 * Resolves a 2026 Multi Round-Trip Request. The returned string must be a JSON
 * object whose keys correspond to the server's inputRequests keys.
 */
public interface McpInputHandler {

    CompletableFuture<String> respond(McpInputRequired inputRequired);
}
