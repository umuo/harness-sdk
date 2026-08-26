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
 * 单个 MCP 服务端的一次 Tool 发现快照。
 *
 * <p>远程 Tool 在此统一适配为 Core 的本地 Tool 契约，因此已有的超时、拦截器、
 * 错误策略和输出限制都会继续生效。默认关闭 ToolSet 时也关闭其客户端。</p>
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
            // namespace 将远程名称转换为合法且抗冲突的本地 Tool 名称。
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
        // 初始化和分页发现完成后才发布不可变快照，避免 Agent 看到半成品注册表。
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
