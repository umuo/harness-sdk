package io.github.gitsilence.agent.mcp;

import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolArguments;
import io.github.gitsilence.agent.tool.ToolOutputStore;
import io.github.gitsilence.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpToolSetTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void adaptsNamesSchemasCallsAndLosslessNonTextResults() throws Exception {
        FakeClient client = new FakeClient(Arrays.asList(
            definition("read_file"),
            definition("read.file")
        ));
        McpToolSet set = McpToolSet.discover(
            client,
            "remote",
            new ToolOutputStore(temporaryDirectory),
            true
        ).get(2, TimeUnit.SECONDS);
        try {
            assertEquals(2, set.getTools().size());
            Tool direct = set.getTools().get(0);
            Tool dotted = set.getTools().get(1);
            assertEquals("remote__read_file", direct.definition().getName());
            assertNotEquals(direct.definition().getName(),
                dotted.definition().getName());
            assertEquals("read.file", set.getLocalToRemoteNames()
                .get(dotted.definition().getName()));
            assertEquals("{\"type\":\"object\",\"properties\":{}}",
                direct.definition().getInputSchema());

            ToolResult result = dotted.execute(
                ToolArguments.parse("{\"binary\":true}"), null
            ).get(2, TimeUnit.SECONDS);
            assertFalse(result.isError());
            assertTrue(result.getContent().contains("exact MCP result saved at"));
            assertEquals(1, result.getOutputReferences().size());
            Path preserved = Paths.get(
                result.getOutputReferences().get(0).getPath()
            );
            assertTrue(Files.exists(preserved));
            assertEquals("{\"content\":[{\"type\":\"image\",\"data\":\"AAAA\"}]}",
                new String(Files.readAllBytes(preserved), "UTF-8"));
        } finally {
            set.close();
        }
        assertTrue(client.closed);
    }

    @Test
    void reportsRemoteToolErrorsAsStructuredToolFailures() throws Exception {
        FakeClient client = new FakeClient(Collections.singletonList(
            definition("fail")
        ));
        McpToolSet set = McpToolSet.discover(client, "remote")
            .get(2, TimeUnit.SECONDS);
        try {
            ToolResult result = set.getTools().get(0).execute(
                ToolArguments.parse("{}"), null
            ).get(2, TimeUnit.SECONDS);
            assertTrue(result.isError());
            assertEquals("MCP_TOOL_ERROR", result.getErrorInfo().getCode());
            assertTrue(result.getErrorInfo().isRetryable());
            assertEquals("fail", result.getErrorInfo().getDetails().get("mcpTool"));
        } finally {
            set.close();
        }
    }

    @Test
    void propagatesAgentToolCancellationToTheMcpCall() throws Exception {
        FakeClient client = new FakeClient(Collections.singletonList(
            definition("pending")
        ));
        McpToolSet set = McpToolSet.discover(client, "remote")
            .get(2, TimeUnit.SECONDS);
        try {
            CompletableFuture<ToolResult> execution = set.getTools().get(0)
                .execute(ToolArguments.parse("{}"), null);
            assertTrue(execution.cancel(true));
            assertTrue(client.pendingCall.isCancelled());
        } finally {
            set.close();
        }
    }

    @Test
    void rejectsToolsThatRequireUnsupportedMcpTasks() {
        McpToolDefinition taskTool = new McpToolDefinition(
            "batch",
            "",
            "Long-running batch",
            "{\"type\":\"object\"}",
            "",
            "{\"name\":\"batch\"}",
            "required"
        );
        FakeClient client = new FakeClient(Collections.singletonList(taskTool));
        java.util.concurrent.ExecutionException error = assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> McpToolSet.discover(client, "remote")
                .get(2, TimeUnit.SECONDS)
        );
        assertEquals("MCP_TASKS_UNSUPPORTED",
            ((McpClientException) error.getCause()).getCode());
    }

    private static McpToolDefinition definition(String name) {
        return new McpToolDefinition(
            name,
            "",
            "Remote " + name,
            "{\"type\":\"object\",\"properties\":{}}",
            "",
            "{\"name\":\"" + name + "\"}"
        );
    }

    private static final class FakeClient implements McpClient {
        private final McpInitializeResult initialized = new McpInitializeResult(
            StdioMcpClient.LATEST_LEGACY_PROTOCOL_VERSION,
            new McpServerInfo("fake", "1", ""),
            "{\"tools\":{}}",
            "",
            true
        );
        private final List<McpToolDefinition> tools;
        private CompletableFuture<McpCallToolResult> pendingCall;
        private boolean closed;

        private FakeClient(List<McpToolDefinition> tools) {
            this.tools = tools;
        }

        @Override
        public CompletableFuture<McpInitializeResult> initialize() {
            return CompletableFuture.completedFuture(initialized);
        }

        @Override
        public CompletableFuture<List<McpToolDefinition>> listTools() {
            return CompletableFuture.completedFuture(tools);
        }

        @Override
        public CompletableFuture<McpCallToolResult> callTool(
                String toolName, String argumentsJson) {
            if ("pending".equals(toolName)) {
                pendingCall = new CompletableFuture<McpCallToolResult>();
                return pendingCall;
            }
            if ("fail".equals(toolName)) {
                return CompletableFuture.completedFuture(new McpCallToolResult(
                    true,
                    "permission denied",
                    "{\"isError\":true}",
                    "",
                    false
                ));
            }
            return CompletableFuture.completedFuture(new McpCallToolResult(
                false,
                "[image omitted]",
                "{\"content\":[{\"type\":\"image\",\"data\":\"AAAA\"}]}",
                "",
                true
            ));
        }

        @Override
        public Optional<McpInitializeResult> getInitializeResult() {
            return Optional.of(initialized);
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
