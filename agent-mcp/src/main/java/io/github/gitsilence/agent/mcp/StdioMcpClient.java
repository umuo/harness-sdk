package io.github.gitsilence.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.runtime.Futures;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java 8 MCP client using the newline-delimited stdio transport. The process is
 * started lazily by {@link #initialize()}.
 */
public final class StdioMcpClient implements McpClient {

    public static final String LATEST_PROTOCOL_VERSION = "2025-11-25";

    private static final List<String> DEFAULT_SUPPORTED_VERSIONS =
        Collections.unmodifiableList(Arrays.asList(
            "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"
        ));

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> command;
    private final Map<String, String> environment;
    private final File workingDirectory;
    private final long requestTimeoutMillis;
    private final long shutdownTimeoutMillis;
    private final int maxMessageBytes;
    private final int maxStderrChars;
    private final int maxToolPages;
    private final int maxTools;
    private final String protocolVersion;
    private final Set<String> supportedProtocolVersions;
    private final String clientName;
    private final String clientVersion;
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentMap<String, PendingRequest> pending =
        new ConcurrentHashMap<String, PendingRequest>();
    private final ScheduledExecutorService scheduler;
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final Object stderrLock = new Object();
    private final StringBuilder stderrTail = new StringBuilder();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile CompletableFuture<McpInitializeResult> initializeFuture;
    private volatile McpInitializeResult initializeResult;
    private volatile boolean closed;

    private StdioMcpClient(Builder builder) {
        this.command = Collections.unmodifiableList(
            new ArrayList<String>(builder.command)
        );
        this.environment = Collections.unmodifiableMap(
            new LinkedHashMap<String, String>(builder.environment)
        );
        this.workingDirectory = builder.workingDirectory;
        this.requestTimeoutMillis = builder.requestTimeoutMillis;
        this.shutdownTimeoutMillis = builder.shutdownTimeoutMillis;
        this.maxMessageBytes = builder.maxMessageBytes;
        this.maxStderrChars = builder.maxStderrChars;
        this.maxToolPages = builder.maxToolPages;
        this.maxTools = builder.maxTools;
        this.protocolVersion = builder.protocolVersion;
        this.supportedProtocolVersions = Collections.unmodifiableSet(
            new LinkedHashSet<String>(builder.supportedProtocolVersions)
        );
        this.clientName = builder.clientName;
        this.clientVersion = builder.clientVersion;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            daemonFactory("agent-mcp-timeout")
        );
    }

    public static Builder builder(String command) {
        return new Builder(command);
    }

    @Override
    public CompletableFuture<McpInitializeResult> initialize() {
        synchronized (lifecycleLock) {
            if (initializeFuture != null) {
                return initializeFuture;
            }
            if (closed) {
                return Futures.failed(new McpClientException(
                    "MCP_CLIENT_CLOSED", "MCP client is closed", false
                ));
            }
            try {
                startProcess();
            } catch (IOException error) {
                initializeFuture = Futures.failed(new McpClientException(
                    "MCP_PROCESS_START_FAILED",
                    "Cannot start MCP server process: " + error.getMessage(),
                    false,
                    error
                ));
                close();
                return initializeFuture;
            }

            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", protocolVersion);
            params.set("capabilities", mapper.createObjectNode());
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", clientName);
            clientInfo.put("version", clientVersion);

            final CompletableFuture<McpInitializeResult> result =
                new CompletableFuture<McpInitializeResult>();
            initializeFuture = result;
            request("initialize", params, false).whenComplete((node, error) -> {
                if (error != null) {
                    result.completeExceptionally(Futures.unwrap(error));
                    close();
                    return;
                }
                final McpInitializeResult parsed;
                try {
                    parsed = parseInitialize(node);
                } catch (RuntimeException parseError) {
                    result.completeExceptionally(parseError);
                    close();
                    return;
                }
                notification("notifications/initialized", null)
                    .whenComplete((ignored, notifyError) -> {
                        if (notifyError != null) {
                            result.completeExceptionally(Futures.unwrap(notifyError));
                            close();
                            return;
                        }
                        initializeResult = parsed;
                        result.complete(parsed);
                    });
            });
            return result;
        }
    }

    @Override
    public CompletableFuture<List<McpToolDefinition>> listTools() {
        return initialize().thenCompose(initialized -> {
            if (!initialized.isToolsSupported()) {
                return Futures.failed(new McpClientException(
                    "MCP_TOOLS_UNSUPPORTED",
                    "MCP server '" + initialized.getServerInfo().getName()
                        + "' did not advertise the tools capability",
                    false
                ));
            }
            List<McpToolDefinition> tools = new ArrayList<McpToolDefinition>();
            Set<String> names = new HashSet<String>();
            return listToolPage(null, 0, tools, names);
        });
    }

    @Override
    public CompletableFuture<McpCallToolResult> callTool(String toolName,
                                                          String argumentsJson) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return Futures.failed(new IllegalArgumentException(
                "toolName must not be blank"
            ));
        }
        final JsonNode arguments;
        try {
            arguments = mapper.readTree(
                argumentsJson == null || argumentsJson.trim().isEmpty()
                    ? "{}" : argumentsJson
            );
            if (arguments == null || !arguments.isObject()) {
                throw new IllegalArgumentException(
                    "MCP Tool arguments must be a JSON object"
                );
            }
        } catch (IOException error) {
            return Futures.failed(new IllegalArgumentException(
                "Invalid MCP Tool arguments JSON: " + error.getMessage(), error
            ));
        } catch (RuntimeException error) {
            return Futures.failed(error);
        }

        final CompletableFuture<McpCallToolResult> output =
            new CompletableFuture<McpCallToolResult>();
        final AtomicReference<CompletableFuture<JsonNode>> activeRequest =
            new AtomicReference<CompletableFuture<JsonNode>>();
        initialize().whenComplete((initialized, initializeError) -> {
            if (initializeError != null) {
                output.completeExceptionally(Futures.unwrap(initializeError));
                return;
            }
            if (output.isCancelled()) {
                return;
            }
            if (!initialized.isToolsSupported()) {
                output.completeExceptionally(new McpClientException(
                    "MCP_TOOLS_UNSUPPORTED",
                    "MCP server did not advertise the tools capability",
                    false
                ));
                return;
            }
            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", arguments);
            CompletableFuture<JsonNode> call = request("tools/call", params, true);
            activeRequest.set(call);
            if (output.isCancelled()) {
                call.cancel(true);
                return;
            }
            call.whenComplete((result, callError) -> {
                if (callError != null) {
                    output.completeExceptionally(Futures.unwrap(callError));
                    return;
                }
                try {
                    output.complete(McpContentRenderer.render(result));
                } catch (RuntimeException renderError) {
                    output.completeExceptionally(renderError);
                }
            });
        });
        output.whenComplete((ignored, error) -> {
            if (output.isCancelled()) {
                CompletableFuture<JsonNode> call = activeRequest.get();
                if (call != null) {
                    call.cancel(true);
                }
            }
        });
        return output;
    }

    @Override
    public Optional<McpInitializeResult> getInitializeResult() {
        return Optional.ofNullable(initializeResult);
    }

    @Override
    public boolean isOpen() {
        Process current = process;
        return !closed && current != null && current.isAlive();
    }

    @Override
    public void close() {
        Process current;
        BufferedWriter currentWriter;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            current = process;
            currentWriter = writer;
        }

        if (currentWriter != null) {
            try {
                currentWriter.close();
            } catch (IOException ignored) {
                // Process termination below is the fallback.
            }
        }
        if (current != null) {
            try {
                if (!current.waitFor(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    current.destroy();
                    if (!current.waitFor(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                        current.destroyForcibly();
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        failPending(new McpClientException(
            "MCP_CLIENT_CLOSED", "MCP client was closed", false
        ));
        scheduler.shutdownNow();
    }

    private CompletableFuture<List<McpToolDefinition>> listToolPage(
            final String cursor,
            final int page,
            final List<McpToolDefinition> tools,
            final Set<String> names) {
        if (page >= maxToolPages) {
            return Futures.failed(new McpClientException(
                "MCP_TOOL_LIST_LIMIT",
                "MCP tools/list exceeded " + maxToolPages + " pages",
                false
            ));
        }
        ObjectNode params = mapper.createObjectNode();
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        return request("tools/list", params, true).thenCompose(result -> {
            JsonNode listed = result.get("tools");
            if (listed == null || !listed.isArray()) {
                return Futures.failed(protocolError(
                    "tools/list result must contain a tools array"
                ));
            }
            try {
                for (JsonNode definition : listed) {
                    McpToolDefinition parsed = parseToolDefinition(definition);
                    if (!names.add(parsed.getName())) {
                        throw protocolError(
                            "tools/list returned duplicate Tool name: "
                                + parsed.getName()
                        );
                    }
                    tools.add(parsed);
                    if (tools.size() > maxTools) {
                        throw new McpClientException(
                            "MCP_TOOL_LIST_LIMIT",
                            "MCP tools/list exceeded " + maxTools + " tools",
                            false
                        );
                    }
                }
            } catch (RuntimeException error) {
                return Futures.failed(error);
            }
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull() || next.asText("").isEmpty()) {
                return CompletableFuture.completedFuture(
                    Collections.unmodifiableList(
                        new ArrayList<McpToolDefinition>(tools)
                    )
                );
            }
            return listToolPage(next.asText(), page + 1, tools, names);
        });
    }

    private McpToolDefinition parseToolDefinition(JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            throw protocolError("MCP Tool definition must be a JSON object");
        }
        JsonNode schema = definition.get("inputSchema");
        if (schema == null || !schema.isObject()) {
            throw protocolError(
                "MCP Tool '" + definition.path("name").asText("?")
                    + "' must have an object inputSchema"
            );
        }
        JsonNode outputSchema = definition.get("outputSchema");
        if (outputSchema != null && !outputSchema.isObject()) {
            throw protocolError("MCP Tool outputSchema must be an object");
        }
        try {
            return new McpToolDefinition(
                definition.path("name").asText(null),
                definition.path("title").asText(""),
                definition.path("description").asText(""),
                schema.toString(),
                outputSchema == null ? "" : outputSchema.toString(),
                definition.toString(),
                definition.path("execution").path("taskSupport").asText("")
            );
        } catch (RuntimeException error) {
            throw protocolError(error.getMessage(), error);
        }
    }

    private McpInitializeResult parseInitialize(JsonNode result) {
        if (result == null || !result.isObject()) {
            throw protocolError("initialize result must be a JSON object");
        }
        String negotiated = result.path("protocolVersion").asText("");
        if (!supportedProtocolVersions.contains(negotiated)) {
            throw new McpClientException(
                "MCP_PROTOCOL_VERSION_UNSUPPORTED",
                "MCP server selected unsupported protocol version '"
                    + negotiated + "'; supported versions are "
                    + supportedProtocolVersions,
                false
            );
        }
        JsonNode capabilities = result.get("capabilities");
        if (capabilities == null || !capabilities.isObject()) {
            throw protocolError("initialize result must contain capabilities");
        }
        if (capabilities.has("tools")
                && !capabilities.get("tools").isObject()) {
            throw protocolError(
                "initialize tools capability must be a JSON object"
            );
        }
        JsonNode info = result.get("serverInfo");
        if (info == null || !info.isObject()) {
            throw protocolError("initialize result must contain serverInfo");
        }
        McpServerInfo serverInfo;
        try {
            serverInfo = new McpServerInfo(
                info.path("name").asText(null),
                info.path("version").asText(""),
                info.path("title").asText("")
            );
        } catch (RuntimeException error) {
            throw protocolError(error.getMessage(), error);
        }
        return new McpInitializeResult(
            negotiated,
            serverInfo,
            capabilities.toString(),
            result.path("instructions").asText(""),
            capabilities.has("tools")
        );
    }

    private CompletableFuture<JsonNode> request(String method,
                                                 ObjectNode params,
                                                 boolean requireInitialized) {
        if (requireInitialized && initializeResult == null) {
            return Futures.failed(new McpClientException(
                "MCP_NOT_INITIALIZED",
                "MCP request '" + method + "' was sent before initialization",
                false
            ));
        }
        if (closed) {
            return Futures.failed(new McpClientException(
                "MCP_CLIENT_CLOSED", "MCP client is closed", false
            ));
        }
        final long numericId = requestIds.incrementAndGet();
        final String id = Long.toString(numericId);
        final PendingRequest request = new PendingRequest(method);
        pending.put(id, request);
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            if (pending.remove(id, request)) {
                request.fail(new McpClientException(
                    "MCP_REQUEST_TIMEOUT",
                    "MCP request '" + method + "' timed out after "
                        + requestTimeoutMillis + " ms",
                    true
                ));
                if (!"initialize".equals(method)) {
                    sendCancellation(numericId, "client request timeout");
                }
            }
        }, requestTimeoutMillis, TimeUnit.MILLISECONDS);
        request.setTimeout(timeout);
        request.future.whenComplete((ignored, error) -> {
            if (request.future.isCancelled()
                    && pending.remove(id, request)) {
                request.markCancelled();
                if (!"initialize".equals(method)) {
                    sendCancellation(numericId, "client request cancelled");
                }
            }
        });

        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", numericId);
        message.put("method", method);
        message.set("params", params);
        try {
            send(message);
        } catch (RuntimeException error) {
            if (pending.remove(id, request)) {
                request.fail(error);
            }
        }
        return request.future;
    }

    private void sendCancellation(long requestId, String reason) {
        if (closed) {
            return;
        }
        ObjectNode cancelled = mapper.createObjectNode();
        cancelled.put("requestId", requestId);
        cancelled.put("reason", reason);
        notification("notifications/cancelled", cancelled);
    }

    private CompletableFuture<Void> notification(String method,
                                                  ObjectNode params) {
        if (closed) {
            return Futures.failed(new McpClientException(
                "MCP_CLIENT_CLOSED", "MCP client is closed", false
            ));
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        try {
            send(message);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException error) {
            return Futures.failed(error);
        }
    }

    private void startProcess() throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory);
        }
        processBuilder.environment().putAll(environment);
        process = processBuilder.start();
        writer = new BufferedWriter(new OutputStreamWriter(
            process.getOutputStream(), StandardCharsets.UTF_8
        ));
        Thread stdout = daemonFactory("agent-mcp-stdout").newThread(
            () -> stdoutLoop(process.getInputStream())
        );
        Thread stderr = daemonFactory("agent-mcp-stderr").newThread(
            () -> stderrLoop(process.getErrorStream())
        );
        stdout.start();
        stderr.start();
    }

    private void send(JsonNode message) {
        synchronized (writeLock) {
            if (closed || writer == null) {
                throw new McpClientException(
                    "MCP_TRANSPORT_CLOSED", "MCP stdio transport is closed", true
                );
            }
            try {
                writer.write(mapper.writeValueAsString(message));
                writer.newLine();
                writer.flush();
            } catch (IOException error) {
                throw transportError("Cannot write to MCP server", error);
            }
        }
    }

    private void stdoutLoop(InputStream input) {
        try {
            String line;
            while ((line = readUtf8Line(input, maxMessageBytes)) != null) {
                if (line.isEmpty()) {
                    throw new IOException("MCP server wrote an empty stdout line");
                }
                handleIncoming(mapper.readTree(line));
            }
            if (!closed) {
                failPending(transportError("MCP server closed stdout", null));
                close();
            }
        } catch (Throwable error) {
            if (!closed) {
                failPending(transportError(
                    "MCP stdio reader failed: " + message(error), error
                ));
                close();
            }
        }
    }

    private void handleIncoming(JsonNode message) {
        if (message == null || !message.isObject()
                || !"2.0".equals(message.path("jsonrpc").asText())) {
            throw protocolError("MCP server emitted invalid JSON-RPC");
        }
        JsonNode idNode = message.get("id");
        if (idNode != null && (message.has("result") || message.has("error"))) {
            PendingRequest request = pending.remove(idNode.asText());
            if (request == null) {
                return;
            }
            JsonNode error = message.get("error");
            if (error != null && !error.isNull()) {
                request.fail(McpClientException.rpc(
                    request.method,
                    error.path("code").asInt(-32000),
                    error.path("message").asText("Unknown MCP JSON-RPC error"),
                    error.has("data") ? error.get("data").toString() : ""
                ));
            } else {
                JsonNode result = message.get("result");
                request.complete(result == null ? NullNode.getInstance() : result);
            }
            return;
        }
        if (message.has("method") && idNode != null) {
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", idNode);
            if ("ping".equals(message.path("method").asText())) {
                response.set("result", mapper.createObjectNode());
            } else {
                ObjectNode error = response.putObject("error");
                error.put("code", -32601);
                error.put("message", "Client method not supported: "
                    + message.path("method").asText());
            }
            send(response);
        }
        // Server notifications are intentionally ignored in the first client.
    }

    private void stderrLoop(InputStream input) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendStderr(line + '\n');
            }
        } catch (IOException error) {
            if (!closed) {
                appendStderr("[stderr capture failed: " + message(error) + "]\n");
            }
        }
    }

    private void appendStderr(String value) {
        synchronized (stderrLock) {
            stderrTail.append(value);
            int excess = stderrTail.length() - maxStderrChars;
            if (excess > 0) {
                stderrTail.delete(0, excess);
            }
        }
    }

    private String stderrSnapshot() {
        synchronized (stderrLock) {
            return stderrTail.toString().trim();
        }
    }

    private void failPending(Throwable error) {
        for (Map.Entry<String, PendingRequest> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().fail(error);
            }
        }
    }

    private McpClientException transportError(String detail, Throwable cause) {
        String stderr = stderrSnapshot();
        String full = stderr.isEmpty() ? detail
            : detail + "; MCP server stderr tail: " + stderr;
        return new McpClientException(
            "MCP_TRANSPORT_ERROR", full, true, cause
        );
    }

    private static McpClientException protocolError(String message) {
        return protocolError(message, null);
    }

    private static McpClientException protocolError(String message,
                                                    Throwable cause) {
        return new McpClientException(
            "MCP_PROTOCOL_ERROR", message, false, cause
        );
    }

    private static String readUtf8Line(InputStream input, int maxBytes)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                break;
            }
            if (next == '\n') {
                break;
            }
            if (bytes.size() >= maxBytes) {
                throw new IOException(
                    "MCP message exceeds " + maxBytes + " bytes"
                );
            }
            bytes.write(next);
        }
        byte[] value = bytes.toByteArray();
        int length = value.length;
        if (length > 0 && value[length - 1] == '\r') {
            length--;
        }
        return new String(value, 0, length, StandardCharsets.UTF_8);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null
            ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static ThreadFactory daemonFactory(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class PendingRequest {
        private final String method;
        private final CompletableFuture<JsonNode> future =
            new CompletableFuture<JsonNode>();
        private ScheduledFuture<?> timeout;
        private boolean done;

        private PendingRequest(String method) {
            this.method = method;
        }

        private synchronized void setTimeout(ScheduledFuture<?> timeout) {
            if (done) {
                timeout.cancel(false);
            } else {
                this.timeout = timeout;
            }
        }

        private synchronized void complete(JsonNode result) {
            if (done) return;
            done = true;
            if (timeout != null) timeout.cancel(false);
            future.complete(result);
        }

        private synchronized void fail(Throwable error) {
            if (done) return;
            done = true;
            if (timeout != null) timeout.cancel(false);
            future.completeExceptionally(error);
        }

        private synchronized void markCancelled() {
            if (done) return;
            done = true;
            if (timeout != null) timeout.cancel(false);
        }
    }

    public static final class Builder {
        private final List<String> command = new ArrayList<String>();
        private final Map<String, String> environment =
            new LinkedHashMap<String, String>();
        private File workingDirectory;
        private long requestTimeoutMillis = 30_000L;
        private long shutdownTimeoutMillis = 2_000L;
        private int maxMessageBytes = 16 * 1024 * 1024;
        private int maxStderrChars = 16 * 1024;
        private int maxToolPages = 100;
        private int maxTools = 2_048;
        private String protocolVersion = LATEST_PROTOCOL_VERSION;
        private final Set<String> supportedProtocolVersions =
            new LinkedHashSet<String>(DEFAULT_SUPPORTED_VERSIONS);
        private String clientName = "agent-sdk";
        private String clientVersion = "0.1.0-SNAPSHOT";

        private Builder(String command) {
            argument(requireText(command, "command"));
        }

        public Builder argument(String argument) {
            command.add(java.util.Objects.requireNonNull(argument, "argument"));
            return this;
        }

        public Builder arguments(String... arguments) {
            if (arguments != null) {
                for (String argument : arguments) argument(argument);
            }
            return this;
        }

        public Builder environment(String name, String value) {
            environment.put(
                requireText(name, "environment name"),
                java.util.Objects.requireNonNull(value, "environment value")
            );
            return this;
        }

        public Builder workingDirectory(File directory) {
            this.workingDirectory = java.util.Objects.requireNonNull(
                directory, "directory"
            );
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeoutMillis = positiveMillis(timeout, "requestTimeout");
            return this;
        }

        public Builder shutdownTimeout(Duration timeout) {
            this.shutdownTimeoutMillis = positiveMillis(timeout, "shutdownTimeout");
            return this;
        }

        public Builder maxMessageBytes(int value) {
            if (value < 1024) throw new IllegalArgumentException(
                "maxMessageBytes must be at least 1024"
            );
            this.maxMessageBytes = value;
            return this;
        }

        public Builder maxStderrChars(int value) {
            if (value < 256) throw new IllegalArgumentException(
                "maxStderrChars must be at least 256"
            );
            this.maxStderrChars = value;
            return this;
        }

        public Builder toolListLimits(int maxPages, int maxTools) {
            if (maxPages < 1 || maxTools < 1) {
                throw new IllegalArgumentException(
                    "Tool list limits must be positive"
                );
            }
            this.maxToolPages = maxPages;
            this.maxTools = maxTools;
            return this;
        }

        public Builder protocolVersion(String preferred,
                                       String... additionallySupported) {
            this.protocolVersion = requireText(preferred, "protocolVersion");
            this.supportedProtocolVersions.clear();
            this.supportedProtocolVersions.add(this.protocolVersion);
            if (additionallySupported != null) {
                for (String version : additionallySupported) {
                    this.supportedProtocolVersions.add(
                        requireText(version, "supported protocol version")
                    );
                }
            }
            return this;
        }

        public Builder clientInfo(String name, String version) {
            this.clientName = requireText(name, "client name");
            this.clientVersion = requireText(version, "client version");
            return this;
        }

        public StdioMcpClient build() {
            return new StdioMcpClient(this);
        }

        private static long positiveMillis(Duration duration, String name) {
            java.util.Objects.requireNonNull(duration, name);
            long millis = duration.toMillis();
            if (millis < 1) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return millis;
        }

        private static String requireText(String value, String name) {
            java.util.Objects.requireNonNull(value, name);
            if (value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
