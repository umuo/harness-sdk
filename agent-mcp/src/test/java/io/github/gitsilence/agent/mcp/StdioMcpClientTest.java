package io.github.gitsilence.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioMcpClientTest {

    @Test
    void performsLifecyclePaginationAndToolCallsOverStdio() throws Exception {
        StdioMcpClient client = client(Duration.ofSeconds(2));
        try {
            McpInitializeResult initialized = client.initialize()
                .get(5, TimeUnit.SECONDS);
            assertEquals("fake-server", initialized.getServerInfo().getName());
            assertEquals(StdioMcpClient.LATEST_LEGACY_PROTOCOL_VERSION,
                initialized.getProtocolVersion());
            assertFalse(initialized.isStateless());
            assertTrue(initialized.isToolsSupported());
            assertTrue(client.isOpen());

            List<McpToolDefinition> tools = client.listTools()
                .get(5, TimeUnit.SECONDS);
            assertEquals(2, tools.size());
            assertEquals("echo", tools.get(0).getName());
            assertEquals("media.fetch", tools.get(1).getName());

            McpCallToolResult result = client.callTool(
                "echo", "{\"value\":\"hello\"}"
            ).get(5, TimeUnit.SECONDS);
            assertFalse(result.isError());
            assertTrue(result.getModelContent().contains("echo:hello"));
            assertEquals("{\"seen\":\"hello\"}",
                result.getStructuredContentJson());

            McpCallToolResult media = client.callTool("media.fetch", "{}")
                .get(5, TimeUnit.SECONDS);
            assertTrue(media.isOmittedFromModelContent());
            assertTrue(media.getRawResultJson().contains("AAAA"));
        } finally {
            client.close();
        }
        assertFalse(client.isOpen());
    }

    @Test
    void supportsStateless2026MetadataCacheHintsAndMrtr() throws Exception {
        final AtomicReference<McpInputRequired> requested =
            new AtomicReference<McpInputRequired>();
        StdioMcpClient client = client(Duration.ofSeconds(2), "stateless")
            .clientCapabilities("{\"elicitation\":{\"form\":{}}}")
            .inputHandler(input -> {
                requested.set(input);
                return java.util.concurrent.CompletableFuture.completedFuture(
                    "{\"confirm\":{\"action\":\"accept\",\"content\":{\"ok\":true}}}"
                );
            })
            .build();
        try {
            McpInitializeResult discovered = client.initialize()
                .get(5, TimeUnit.SECONDS);
            assertTrue(discovered.isStateless());
            assertEquals(StdioMcpClient.LATEST_PROTOCOL_VERSION,
                discovered.getProtocolVersion());
            assertEquals("stateless-server",
                discovered.getServerInfo().getName());
            assertEquals(60_000L, discovered.getDiscoveryTtlMillis());

            McpToolCatalog catalog = client.listToolCatalog()
                .get(5, TimeUnit.SECONDS);
            assertEquals(2, catalog.getTools().size());
            assertEquals(2_000L, catalog.getTtlMillis());
            assertEquals("private", catalog.getCacheScope());
            assertTrue(catalog.isFresh(System.currentTimeMillis()));

            McpCallToolResult result = client.callTool(
                "echo", "{\"value\":\"needs-input\"}"
            ).get(5, TimeUnit.SECONDS);
            assertTrue(result.getModelContent().contains("confirmed:true"));
            assertEquals(1, requested.get().getRound());
            assertEquals("opaque-state", requested.get().getRequestState());
            assertTrue(requested.get().getInputRequestsJson()
                .contains("elicitation/create"));
        } finally {
            client.close();
        }
    }

    @Test
    void doesNotTreatRecognizedModernErrorAsLegacyServer() throws Exception {
        try (StdioMcpClient client = client(
                Duration.ofSeconds(2), "modern-error").build()) {
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> client.initialize().get(5, TimeUnit.SECONDS)
            );
            assertTrue(failure.getCause() instanceof McpClientException);
            McpClientException cause = (McpClientException) failure.getCause();
            assertEquals(Integer.valueOf(-32022), cause.getRpcCode());
        }
    }

    @Test
    void timesOutUnansweredRequestsWithStableError() throws Exception {
        // A Java 8 child JVM can take noticeably longer to start on CI.
        StdioMcpClient client = client(Duration.ofSeconds(2));
        try {
            client.initialize().get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(
                ExecutionException.class,
                () -> client.callTool("hang", "{}").get(5, TimeUnit.SECONDS)
            );
            McpClientException cause = (McpClientException) error.getCause();
            assertEquals("MCP_REQUEST_TIMEOUT", cause.getCode());
            assertTrue(cause.isRetryable());
        } finally {
            client.close();
        }
    }

    private static StdioMcpClient client(Duration timeout) {
        return client(timeout, "legacy").build();
    }

    private static StdioMcpClient.Builder client(Duration timeout,
                                                  String mode) {
        String executable = isWindows() ? "java.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty(
            "surefire.test.class.path", System.getProperty("java.class.path")
        );
        return StdioMcpClient.builder(java.toString())
            .arguments("-cp", classpath, FakeMcpServer.class.getName(), mode)
            .requestTimeout(timeout)
            .shutdownTimeout(Duration.ofMillis(500));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static final class FakeMcpServer {

        private FakeMcpServer() {
        }

        public static void main(String[] args) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            BufferedReader input = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8
            ));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                System.out, StandardCharsets.UTF_8
            ));
            System.err.println("fake MCP diagnostic");
            String mode = args.length > 0 ? args[0] : "legacy";
            boolean stateless = "stateless".equals(mode);
            boolean initialized = stateless;
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode message = mapper.readTree(line);
                String method = message.path("method").asText("");
                if ("server/discover".equals(method)) {
                    if ("modern-error".equals(mode)) {
                        rpcError(mapper, output, message.get("id"), -32022,
                            "unsupported protocol version");
                        continue;
                    }
                    if (!stateless) {
                        rpcError(mapper, output, message.get("id"), -32601,
                            "method not found");
                        continue;
                    }
                    if (!has2026Meta(message)) {
                        rpcError(mapper, output, message.get("id"), -32602,
                            "missing request metadata");
                        continue;
                    }
                    ObjectNode result = completeResult(mapper);
                    result.put("ttlMs", 60_000);
                    result.put("cacheScope", "public");
                    result.putArray("supportedVersions")
                        .add(StdioMcpClient.LATEST_PROTOCOL_VERSION);
                    result.putObject("capabilities").putObject("tools")
                        .put("listChanged", true);
                    ObjectNode info = result.putObject("_meta").putObject(
                        "io.modelcontextprotocol/serverInfo"
                    );
                    info.put("name", "stateless-server");
                    info.put("version", "2.0.0");
                    respond(mapper, output, message.get("id"), result);
                    continue;
                }
                if ("notifications/initialized".equals(method)) {
                    initialized = true;
                    ObjectNode ping = mapper.createObjectNode();
                    ping.put("jsonrpc", "2.0");
                    ping.put("id", "server-ping");
                    ping.put("method", "ping");
                    output.write(mapper.writeValueAsString(ping));
                    output.newLine();
                    output.flush();
                    continue;
                }
                if ("notifications/cancelled".equals(method)) {
                    continue;
                }
                if ("initialize".equals(method)) {
                    if (stateless) {
                        rpcError(mapper, output, message.get("id"), -32601,
                            "initialize was removed");
                        continue;
                    }
                    ObjectNode result = mapper.createObjectNode();
                    result.put("protocolVersion",
                        message.path("params").path("protocolVersion").asText());
                    result.putObject("capabilities").putObject("tools")
                        .put("listChanged", false);
                    ObjectNode info = result.putObject("serverInfo");
                    info.put("name", "fake-server");
                    info.put("version", "1.0.0");
                    respond(mapper, output, message.get("id"), result);
                    continue;
                }
                if (method.isEmpty() && message.has("result")) {
                    continue;
                }
                if (!initialized) {
                    rpcError(mapper, output, message.get("id"), -32002,
                        "not initialized");
                    continue;
                }
                if ("tools/list".equals(method)) {
                    if (stateless && !has2026Meta(message)) {
                        rpcError(mapper, output, message.get("id"), -32602,
                            "missing request metadata");
                        continue;
                    }
                    ObjectNode result = stateless
                        ? completeResult(mapper) : mapper.createObjectNode();
                    if (stateless) {
                        result.put("ttlMs",
                            message.path("params").has("cursor") ? 2_000 : 5_000);
                        result.put("cacheScope",
                            message.path("params").has("cursor")
                                ? "private" : "public");
                    }
                    ArrayNode tools = result.putArray("tools");
                    if (!message.path("params").has("cursor")) {
                        ObjectNode echo = tools.addObject();
                        echo.put("name", "echo");
                        echo.put("description", "Echo a value");
                        ObjectNode schema = echo.putObject("inputSchema");
                        schema.put("type", "object");
                        schema.putObject("properties").putObject("value")
                            .put("type", "string");
                        result.put("nextCursor", "page-2");
                    } else {
                        ObjectNode media = tools.addObject();
                        media.put("name", "media.fetch");
                        media.putObject("inputSchema").put("type", "object");
                    }
                    respond(mapper, output, message.get("id"), result);
                    continue;
                }
                if ("tools/call".equals(method)) {
                    if (stateless && !has2026Meta(message)) {
                        rpcError(mapper, output, message.get("id"), -32602,
                            "missing request metadata");
                        continue;
                    }
                    String name = message.path("params").path("name").asText();
                    if ("hang".equals(name)) {
                        continue;
                    }
                    if (stateless && "needs-input".equals(
                            message.path("params").path("arguments")
                                .path("value").asText())
                            && !message.path("params").has("inputResponses")) {
                        ObjectNode required = mapper.createObjectNode();
                        required.put("resultType", "input_required");
                        required.put("requestState", "opaque-state");
                        ObjectNode elicitation = required
                            .putObject("inputRequests")
                            .putObject("confirm");
                        elicitation.put("method", "elicitation/create");
                        elicitation.putObject("params")
                            .put("message", "Confirm operation");
                        respond(mapper, output, message.get("id"), required);
                        continue;
                    }
                    ObjectNode result = stateless
                        ? completeResult(mapper) : mapper.createObjectNode();
                    ArrayNode content = result.putArray("content");
                    if ("media.fetch".equals(name)) {
                        ObjectNode image = content.addObject();
                        image.put("type", "image");
                        image.put("mimeType", "image/png");
                        image.put("data", "AAAA");
                    } else {
                        String value = message.path("params").path("arguments")
                            .path("value").asText();
                        boolean confirmed = message.path("params")
                            .path("inputResponses").path("confirm")
                            .path("action").asText("").equals("accept");
                        content.addObject().put("type", "text")
                            .put("text", confirmed
                                ? "confirmed:true" : "echo:" + value);
                        result.putObject("structuredContent").put("seen", value);
                    }
                    respond(mapper, output, message.get("id"), result);
                    continue;
                }
                rpcError(mapper, output, message.get("id"), -32601,
                    "method not found");
            }
        }

        private static boolean has2026Meta(JsonNode message) {
            JsonNode meta = message.path("params").path("_meta");
            return StdioMcpClient.LATEST_PROTOCOL_VERSION.equals(
                    meta.path("io.modelcontextprotocol/protocolVersion").asText())
                && meta.path("io.modelcontextprotocol/clientCapabilities")
                    .isObject()
                && meta.path("io.modelcontextprotocol/clientInfo").isObject();
        }

        private static ObjectNode completeResult(ObjectMapper mapper) {
            ObjectNode result = mapper.createObjectNode();
            result.put("resultType", "complete");
            return result;
        }

        private static void respond(ObjectMapper mapper,
                                    BufferedWriter output,
                                    JsonNode id,
                                    JsonNode result) throws Exception {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            output.write(mapper.writeValueAsString(response));
            output.newLine();
            output.flush();
        }

        private static void rpcError(ObjectMapper mapper,
                                     BufferedWriter output,
                                     JsonNode id,
                                     int code,
                                     String message) throws Exception {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            ObjectNode error = response.putObject("error");
            error.put("code", code);
            error.put("message", message);
            output.write(mapper.writeValueAsString(response));
            output.newLine();
            output.flush();
        }
    }
}
