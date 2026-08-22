package io.github.gitsilence.agent.mcp;

import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolOutputStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A discovered snapshot of one MCP server's Tools. Closing the set closes its
 * client by default; an Agent may keep using the Tools only while it remains open.
 */
public final class McpToolSet implements AutoCloseable {

    private final McpClient client;
    private final McpInitializeResult initializeResult;
    private final McpToolCatalog catalog;
    private final List<Tool> tools;
    private final Map<String, String> localToRemoteNames;
    private final boolean closeClient;

    private McpToolSet(McpClient client,
                       McpInitializeResult initializeResult,
                       McpToolCatalog catalog,
                       String namespace,
                       ToolOutputStore outputStore,
                       boolean closeClient) {
        this.client = client;
        this.initializeResult = initializeResult;
        this.catalog = catalog;
        this.closeClient = closeClient;
        List<Tool> adapted = new ArrayList<Tool>();
        Map<String, String> names = new LinkedHashMap<String, String>();
        for (McpToolDefinition definition : catalog.getTools()) {
            if ("required".equals(definition.getTaskSupport())) {
                throw new McpClientException(
                    "MCP_TASKS_UNSUPPORTED",
                    "MCP Tool '" + definition.getName()
                        + "' requires experimental MCP task execution, which "
                        + "this Tool adapter does not support",
                    false
                );
            }
            Tool tool = new McpToolAdapter(
                client, initializeResult, definition, namespace, outputStore
            );
            String previous = names.put(
                tool.definition().getName(), definition.getName()
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                    "MCP Tool names collide after local adaptation: "
                        + previous + " and " + definition.getName()
                );
            }
            adapted.add(tool);
        }
        this.tools = Collections.unmodifiableList(adapted);
        this.localToRemoteNames = Collections.unmodifiableMap(names);
    }

    public static CompletableFuture<McpToolSet> discover(McpClient client,
                                                          String namespace) {
        return discover(
            client, namespace, ToolOutputStore.systemTemporary(), true
        );
    }

    public static CompletableFuture<McpToolSet> discover(McpClient client,
                                                          String namespace,
                                                          ToolOutputStore outputStore,
                                                          boolean closeClient) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(outputStore, "outputStore");
        McpToolNames.validateNamespace(namespace);
        CompletableFuture<McpToolSet> discovered = client.initialize()
            .thenCompose(initialized -> client.listToolCatalog()
                .thenApply(catalog -> new McpToolSet(
                    client,
                    initialized,
                    catalog,
                    namespace,
                    outputStore,
                    closeClient
                )));
        discovered.whenComplete((ignored, error) -> {
            if (error != null && closeClient) {
                client.close();
            }
        });
        return discovered;
    }

    public List<Tool> getTools() {
        return tools;
    }

    public McpInitializeResult getInitializeResult() {
        return initializeResult;
    }

    public McpToolCatalog getCatalog() {
        return catalog;
    }

    public Map<String, String> getLocalToRemoteNames() {
        return localToRemoteNames;
    }

    public McpClient getClient() {
        return client;
    }

    @Override
    public void close() {
        if (closeClient) {
            client.close();
        }
    }
}
