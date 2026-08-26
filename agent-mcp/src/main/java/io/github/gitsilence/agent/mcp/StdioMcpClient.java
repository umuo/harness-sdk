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
 * 使用换行分隔 stdio 传输的 Java 8 MCP 客户端。
 *
 * <p>子进程在 {@link #initialize()} 时延迟启动。客户端负责 JSON-RPC 请求关联、
 * 超时与取消、工具分页发现、多轮 input_required，以及现代无状态协议到传统有状态
 * 协议的自动回退。</p>
 */
public final class StdioMcpClient implements McpClient {

    public static final String LATEST_PROTOCOL_VERSION = "2026-07-28";
    public static final String LATEST_LEGACY_PROTOCOL_VERSION = "2025-11-25";

    private static final List<String> DEFAULT_SUPPORTED_VERSIONS =
        Collections.unmodifiableList(Arrays.asList(
            "2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26",
            "2024-11-05"
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
    private final McpProtocolMode protocolMode;
    private final String clientName;
    private final String clientVersion;
    private final ObjectNode clientCapabilities;
    private final McpInputHandler inputHandler;
    private final int maxInputRounds;
    /** JSON-RPC id 生成器和等待响应的请求表共同完成异步请求关联。 */
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentMap<String, PendingRequest> pending =
        new ConcurrentHashMap<String, PendingRequest>();
    private final ScheduledExecutorService scheduler;
    /** 生命周期、stdin 写入和 stderr 尾部各自使用独立锁，避免无关操作互相阻塞。 */
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
        this.protocolMode = builder.protocolMode;
        this.clientName = builder.clientName;
        this.clientVersion = builder.clientVersion;
        this.clientCapabilities = parseObject(
            builder.clientCapabilitiesJson, "clientCapabilities"
        );
        this.inputHandler = builder.inputHandler;
        this.maxInputRounds = builder.maxInputRounds;
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
            // 初始化 future 被缓存，多个并发调用方共享同一次协商。
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

            final CompletableFuture<McpInitializeResult> result =
                new CompletableFuture<McpInitializeResult>();
            initializeFuture = result;
            if (protocolMode == McpProtocolMode.LEGACY) {
                initializeLegacy(result);
            } else {
                discoverStateless(result);
            }
            return result;
        }
    }

    private void discoverStateless(
            final CompletableFuture<McpInitializeResult> output) {
        // 先探测 2026 无状态协议；AUTO 模式下仅在“服务端不认识该协议”时回退。
        ObjectNode params = mapper.createObjectNode();
        attachRequestMeta(params, LATEST_PROTOCOL_VERSION);
        request("server/discover", params, false).whenComplete((node, error) -> {
            if (error != null) {
                if (protocolMode == McpProtocolMode.AUTO
                        && !isRecognizedModernError(error)) {
                    fallbackToLegacy(output);
                } else {
                    failPreparation(output, Futures.unwrap(error));
                }
                return;
            }
            try {
                McpInitializeResult discovered = parseDiscover(node);
                initializeResult = discovered;
                output.complete(discovered);
            } catch (RuntimeException parseError) {
                // 收到合法 JSON-RPC result 已证明它是现代服务端；现代响应内容错误时
                // 不能再用相同配置重试传统有状态协议，否则会掩盖真正的协议错误。
                failPreparation(output, parseError);
            }
        });
    }

    private void fallbackToLegacy(
            CompletableFuture<McpInitializeResult> output) {
        try {
            // 传统协议包含进程级会话状态，因此必须重启探测用的子进程再初始化。
            restartProcess();
            initializeLegacy(output);
        } catch (Throwable error) {
            failPreparation(output, new McpClientException(
                "MCP_LEGACY_FALLBACK_FAILED",
                "Cannot restart MCP server for legacy negotiation: "
                    + message(error),
                true,
                error
            ));
        }
    }

    private void initializeLegacy(
            final CompletableFuture<McpInitializeResult> output) {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", legacyProtocolVersion());
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", clientName);
        clientInfo.put("version", clientVersion);
        request("initialize", params, false).whenComplete((node, error) -> {
            if (error != null) {
                failPreparation(output, Futures.unwrap(error));
                return;
            }
            final McpInitializeResult parsed;
            try {
                parsed = parseInitialize(node);
            } catch (RuntimeException parseError) {
                failPreparation(output, parseError);
                return;
            }
            notification("notifications/initialized", null)
                .whenComplete((ignored, notifyError) -> {
                    if (notifyError != null) {
                        failPreparation(output, Futures.unwrap(notifyError));
                        return;
                    }
                    initializeResult = parsed;
                    output.complete(parsed);
                });
        });
    }

    private void failPreparation(CompletableFuture<McpInitializeResult> output,
                                 Throwable error) {
        output.completeExceptionally(error);
        close();
    }

    @Override
    public CompletableFuture<List<McpToolDefinition>> listTools() {
        return listToolCatalog().thenApply(McpToolCatalog::getTools);
    }

    @Override
    public CompletableFuture<McpToolCatalog> listToolCatalog() {
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
            return listToolPage(
                null, 0, tools, names, new CatalogAccumulator()
            );
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
        // 一轮 Tool 调用可能在 MCP 请求和本地 inputHandler future 之间切换。
        final AtomicReference<CompletableFuture<?>> activeOperation =
            new AtomicReference<CompletableFuture<?>>();
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
            callToolRound(
                toolName, arguments, null, null, 0, output, activeOperation
            );
        });
        output.whenComplete((ignored, error) -> {
            if (output.isCancelled()) {
                CompletableFuture<?> active = activeOperation.get();
                if (active != null) {
                    active.cancel(true);
                }
            }
        });
        return output;
    }

    private void callToolRound(
            final String toolName,
            final JsonNode arguments,
            final JsonNode inputResponses,
            final String requestState,
            final int round,
            final CompletableFuture<McpCallToolResult> output,
            final AtomicReference<CompletableFuture<?>> activeOperation) {
        if (output.isCancelled()) return;
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        if (inputResponses != null) {
            params.set("inputResponses", inputResponses);
        }
        if (requestState != null && !requestState.isEmpty()) {
            params.put("requestState", requestState);
        }
        attachRequestMetaIfStateless(params);

        CompletableFuture<JsonNode> call = request("tools/call", params, true);
        activeOperation.set(call);
        if (output.isCancelled()) {
            call.cancel(true);
            return;
        }
        call.whenComplete((result, callError) -> {
            if (callError != null) {
                output.completeExceptionally(Futures.unwrap(callError));
                return;
            }
            final String resultType;
            try {
                resultType = resultType(result, "tools/call");
            } catch (RuntimeException protocolFailure) {
                output.completeExceptionally(protocolFailure);
                return;
            }
            if ("complete".equals(resultType)) {
                try {
                    output.complete(McpContentRenderer.render(result));
                } catch (RuntimeException renderError) {
                    output.completeExceptionally(renderError);
                }
                return;
            }
            if (!"input_required".equals(resultType)) {
                output.completeExceptionally(unsupportedResultType(
                    "tools/call", resultType
                ));
                return;
            }
            // 服务端需要补充输入时交给宿主回调，随后带 requestState 发起下一轮。
            resolveInputRequired(
                toolName, arguments, round, result, output, activeOperation
            );
        });
    }

    private void resolveInputRequired(
            final String toolName,
            final JsonNode arguments,
            final int round,
            final JsonNode result,
            final CompletableFuture<McpCallToolResult> output,
            final AtomicReference<CompletableFuture<?>> activeOperation) {
        if (round >= maxInputRounds) {
            output.completeExceptionally(new McpClientException(
                "MCP_INPUT_ROUND_LIMIT",
                "MCP Tool '" + toolName + "' exceeded " + maxInputRounds
                    + " input_required rounds",
                false
            ));
            return;
        }
        final McpInputRequired required;
        try {
            required = parseInputRequired(toolName, round + 1, result);
        } catch (RuntimeException parseError) {
            output.completeExceptionally(parseError);
            return;
        }
        if (inputHandler == null) {
            output.completeExceptionally(new McpClientException(
                "MCP_INPUT_REQUIRED",
                "MCP Tool '" + toolName + "' requires additional input, but "
                    + "no McpInputHandler is configured; inputRequests="
                    + abbreviate(required.getInputRequestsJson(), 512),
                false
            ));
            return;
        }

        final CompletableFuture<String> handled;
        try {
            handled = inputHandler.respond(required);
            if (handled == null) {
                throw new IllegalStateException(
                    "McpInputHandler returned null instead of a CompletableFuture"
                );
            }
        } catch (Throwable handlerError) {
            output.completeExceptionally(inputHandlerError(handlerError));
            return;
        }
        activeOperation.set(handled);
        if (output.isCancelled()) {
            handled.cancel(true);
            return;
        }
        handled.whenComplete((responsesJson, handlerError) -> {
            if (handlerError != null) {
                output.completeExceptionally(inputHandlerError(
                    Futures.unwrap(handlerError)
                ));
                return;
            }
            final ObjectNode responses;
            try {
                responses = parseObject(responsesJson, "MCP input responses");
            } catch (RuntimeException parseError) {
                output.completeExceptionally(inputHandlerError(parseError));
                return;
            }
            callToolRound(
                toolName,
                arguments,
                responses,
                required.getRequestState(),
                round + 1,
                output,
                activeOperation
            );
        });
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

        stopProcess(current, currentWriter);
        failPending(new McpClientException(
            "MCP_CLIENT_CLOSED", "MCP client was closed", false
        ));
        scheduler.shutdownNow();
    }

    private CompletableFuture<McpToolCatalog> listToolPage(
            final String cursor,
            final int page,
            final List<McpToolDefinition> tools,
            final Set<String> names,
            final CatalogAccumulator catalog) {
        // 页数和工具总数双重上限用于防御错误或恶意的无限分页服务端。
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
        attachRequestMetaIfStateless(params);
        return request("tools/list", params, true).thenCompose(result -> {
            try {
                requireCompleteResult(result, "tools/list");
                if (initializeResult.isStateless()) {
                    catalog.record(parseCacheHint(result, "tools/list"));
                }
            } catch (RuntimeException error) {
                return Futures.failed(error);
            }
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
                    catalog.toCatalog(tools)
                );
            }
            return listToolPage(
                next.asText(), page + 1, tools, names, catalog
            );
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
        if (initializeResult != null && initializeResult.isStateless()
                && !"object".equals(schema.path("type").asText())) {
            throw protocolError(
                "MCP 2026 Tool '" + definition.path("name").asText("?")
                    + "' inputSchema must declare type=object"
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
        if (!supportedProtocolVersions.contains(negotiated)
                || LATEST_PROTOCOL_VERSION.equals(negotiated)) {
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

    private McpInitializeResult parseDiscover(JsonNode result) {
        if (result == null || !result.isObject()) {
            throw protocolError("server/discover result must be a JSON object");
        }
        requireResultType(result, "server/discover", true);
        JsonNode versionsNode = result.get("supportedVersions");
        if (versionsNode == null || !versionsNode.isArray()
                || versionsNode.size() == 0) {
            throw protocolError(
                "server/discover must contain supportedVersions"
            );
        }
        List<String> versions = new ArrayList<String>();
        Set<String> unique = new LinkedHashSet<String>();
        for (JsonNode version : versionsNode) {
            if (!version.isTextual() || version.asText().trim().isEmpty()) {
                throw protocolError(
                    "server/discover supportedVersions must contain strings"
                );
            }
            if (unique.add(version.asText())) {
                versions.add(version.asText());
            }
        }
        if (!unique.contains(LATEST_PROTOCOL_VERSION)
                || !supportedProtocolVersions.contains(LATEST_PROTOCOL_VERSION)) {
            throw new McpClientException(
                "MCP_PROTOCOL_VERSION_UNSUPPORTED",
                "MCP server does not support stateless protocol "
                    + LATEST_PROTOCOL_VERSION + "; server versions are "
                    + versions,
                false
            );
        }

        JsonNode capabilities = result.get("capabilities");
        validateCapabilities(capabilities, "server/discover");
        CacheHint cache = parseCacheHint(result, "server/discover");
        JsonNode info = result.path("_meta").get(
            "io.modelcontextprotocol/serverInfo"
        );
        McpServerInfo serverInfo = info != null && info.isObject()
            ? parseServerInfo(info, "server/discover")
            : new McpServerInfo("mcp-server", "", "");
        return new McpInitializeResult(
            LATEST_PROTOCOL_VERSION,
            serverInfo,
            capabilities.toString(),
            result.path("instructions").asText(""),
            capabilities.has("tools"),
            true,
            versions,
            cache.ttlMillis,
            cache.scope
        );
    }

    private void validateCapabilities(JsonNode capabilities, String method) {
        if (capabilities == null || !capabilities.isObject()) {
            throw protocolError(method + " result must contain capabilities");
        }
        if (capabilities.has("tools")
                && !capabilities.get("tools").isObject()) {
            throw protocolError(method + " tools capability must be an object");
        }
    }

    private McpServerInfo parseServerInfo(JsonNode info, String method) {
        try {
            return new McpServerInfo(
                info.path("name").asText(null),
                info.path("version").asText(""),
                info.path("title").asText("")
            );
        } catch (RuntimeException error) {
            throw protocolError(method + " serverInfo is invalid: "
                + error.getMessage(), error);
        }
    }

    private McpInputRequired parseInputRequired(String toolName,
                                                 int round,
                                                 JsonNode result) {
        JsonNode requests = result.get("inputRequests");
        JsonNode state = result.get("requestState");
        if (requests != null && !requests.isObject()) {
            throw protocolError(
                "tools/call inputRequests must be a JSON object"
            );
        }
        if (state != null && !state.isTextual()) {
            throw protocolError("tools/call requestState must be a string");
        }
        if (requests == null && state == null) {
            throw protocolError(
                "tools/call input_required result must contain inputRequests "
                    + "or requestState"
            );
        }
        return new McpInputRequired(
            toolName,
            round,
            requests == null ? "{}" : requests.toString(),
            state == null ? "" : state.asText(),
            result.toString()
        );
    }

    private String resultType(JsonNode result, String method) {
        if (result == null || !result.isObject()) {
            throw protocolError(method + " result must be a JSON object");
        }
        JsonNode type = result.get("resultType");
        if (type == null || type.isNull()) {
            if (initializeResult != null && initializeResult.isStateless()) {
                throw protocolError(
                    method + " result must contain resultType for MCP "
                        + LATEST_PROTOCOL_VERSION
                );
            }
            return "complete";
        }
        if (!type.isTextual() || type.asText().trim().isEmpty()) {
            throw protocolError(method + " resultType must be a string");
        }
        return type.asText();
    }

    private void requireCompleteResult(JsonNode result, String method) {
        requireResultType(
            result,
            method,
            initializeResult != null && initializeResult.isStateless()
        );
    }

    private void requireResultType(JsonNode result,
                                   String method,
                                   boolean required) {
        if (result == null || !result.isObject()) {
            throw protocolError(method + " result must be a JSON object");
        }
        JsonNode type = result.get("resultType");
        if (type == null || type.isNull()) {
            if (required) {
                throw protocolError(
                    method + " result must contain resultType for MCP "
                        + LATEST_PROTOCOL_VERSION
                );
            }
            return;
        }
        if (!type.isTextual() || !"complete".equals(type.asText())) {
            throw unsupportedResultType(method, type.asText(""));
        }
    }

    private CacheHint parseCacheHint(JsonNode result, String method) {
        JsonNode ttl = result.get("ttlMs");
        JsonNode scope = result.get("cacheScope");
        if (ttl == null || !ttl.isIntegralNumber()
                || !ttl.canConvertToLong() || ttl.longValue() < 0) {
            throw protocolError(
                method + " result must contain a non-negative integer ttlMs"
            );
        }
        if (scope == null || !scope.isTextual()
                || !"public".equals(scope.asText())
                    && !"private".equals(scope.asText())) {
            throw protocolError(
                method + " result cacheScope must be public or private"
            );
        }
        return new CacheHint(ttl.longValue(), scope.asText());
    }

    private McpClientException unsupportedResultType(String method,
                                                     String type) {
        return new McpClientException(
            "MCP_RESULT_TYPE_UNSUPPORTED",
            "MCP request '" + method + "' returned unsupported resultType '"
                + type + "'",
            false
        );
    }

    private McpClientException inputHandlerError(Throwable error) {
        return new McpClientException(
            "MCP_INPUT_HANDLER_FAILED",
            "MCP input handler failed: " + message(error),
            false,
            error
        );
    }

    private boolean isRecognizedModernError(Throwable error) {
        McpClientException mcp = find(error, McpClientException.class);
        if (mcp == null || mcp.getRpcCode() == null) return false;
        int rpcCode = mcp.getRpcCode();
        return rpcCode == -32020 || rpcCode == -32021 || rpcCode == -32022;
    }

    private String legacyProtocolVersion() {
        if (!LATEST_PROTOCOL_VERSION.equals(protocolVersion)) {
            return protocolVersion;
        }
        for (String candidate : supportedProtocolVersions) {
            if (!LATEST_PROTOCOL_VERSION.equals(candidate)) {
                return candidate;
            }
        }
        throw new McpClientException(
            "MCP_LEGACY_VERSION_UNAVAILABLE",
            "No legacy MCP protocol version is configured",
            false
        );
    }

    private void attachRequestMetaIfStateless(ObjectNode params) {
        McpInitializeResult prepared = initializeResult;
        if (prepared != null && prepared.isStateless()) {
            attachRequestMeta(params, prepared.getProtocolVersion());
        }
    }

    private void attachRequestMeta(ObjectNode params, String version) {
        ObjectNode meta = mapper.createObjectNode();
        meta.put("io.modelcontextprotocol/protocolVersion", version);
        ObjectNode clientInfo = meta.putObject(
            "io.modelcontextprotocol/clientInfo"
        );
        clientInfo.put("name", clientName);
        clientInfo.put("version", clientVersion);
        meta.set(
            "io.modelcontextprotocol/clientCapabilities",
            clientCapabilities.deepCopy()
        );
        params.set("_meta", meta);
    }

    private ObjectNode parseObject(String json, String name) {
        try {
            JsonNode parsed = mapper.readTree(json == null ? "" : json);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException(name + " must be a JSON object");
            }
            return (ObjectNode) parsed;
        } catch (IOException error) {
            throw new IllegalArgumentException(
                name + " is not valid JSON: " + error.getMessage(), error
            );
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 3) + "...";
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
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
        // 先登记 pending，再写入 stdio，避免极快响应先于关联表注册到达。
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
                if (shouldSendCancellation(method)) {
                    sendCancellation(numericId, "client request timeout");
                }
            }
        }, requestTimeoutMillis, TimeUnit.MILLISECONDS);
        request.setTimeout(timeout);
        request.future.whenComplete((ignored, error) -> {
            if (request.future.isCancelled()
                    && pending.remove(id, request)) {
                request.markCancelled();
                if (shouldSendCancellation(method)) {
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

    private boolean shouldSendCancellation(String method) {
        // AUTO 探测失败会同步重启子进程协商传统协议；不能让旧探测请求的取消通知
        // 与替换后的新进程发生竞态。
        return !"initialize".equals(method)
            && !(protocolMode == McpProtocolMode.AUTO
                && "server/discover".equals(method));
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
        final Process started = processBuilder.start();
        final BufferedWriter startedWriter = new BufferedWriter(
            new OutputStreamWriter(
                started.getOutputStream(), StandardCharsets.UTF_8
            )
        );
        process = started;
        writer = startedWriter;
        Thread stdout = daemonFactory("agent-mcp-stdout").newThread(
            () -> stdoutLoop(started.getInputStream(), started)
        );
        Thread stderr = daemonFactory("agent-mcp-stderr").newThread(
            () -> stderrLoop(started.getErrorStream(), started)
        );
        stdout.start();
        stderr.start();
    }

    private void restartProcess() throws IOException {
        final Process previous;
        final BufferedWriter previousWriter;
        synchronized (lifecycleLock) {
            if (closed) throw new IOException("MCP client is closed");
            previous = process;
            previousWriter = writer;
            process = null;
            writer = null;
        }
        stopProcess(previous, previousWriter);
        synchronized (stderrLock) {
            stderrTail.setLength(0);
        }
        synchronized (lifecycleLock) {
            if (closed) throw new IOException("MCP client is closed");
            startProcess();
        }
    }

    private void stopProcess(Process target, BufferedWriter targetWriter) {
        if (targetWriter != null) {
            try {
                targetWriter.close();
            } catch (IOException ignored) {
                // 关闭 stdin 失败时，下面的进程终止逻辑仍会兜底。
            }
        }
        if (target == null) return;
        try {
            if (!target.waitFor(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                target.destroy();
                if (!target.waitFor(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    target.destroyForcibly();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        }
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

    private void stdoutLoop(InputStream input, Process owner) {
        try {
            String line;
            while ((line = readUtf8Line(input, maxMessageBytes)) != null) {
                if (line.isEmpty()) {
                    throw new IOException("MCP server wrote an empty stdout line");
                }
                handleIncoming(mapper.readTree(line));
            }
            if (!closed && process == owner) {
                failPending(transportError("MCP server closed stdout", null));
                if (process == owner) close();
            }
        } catch (Throwable error) {
            if (!closed && process == owner) {
                failPending(transportError(
                    "MCP stdio reader failed: " + message(error), error
                ));
                if (process == owner) close();
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

    private void stderrLoop(InputStream input, Process owner) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (process == owner) appendStderr(line + '\n');
            }
        } catch (IOException error) {
            if (!closed && process == owner) {
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

    private static final class CacheHint {
        private final long ttlMillis;
        private final String scope;

        private CacheHint(long ttlMillis, String scope) {
            this.ttlMillis = ttlMillis;
            this.scope = scope;
        }
    }

    private static final class CatalogAccumulator {
        private long ttlMillis = Long.MAX_VALUE;
        private String scope = "public";
        private boolean recorded;

        private void record(CacheHint hint) {
            recorded = true;
            ttlMillis = Math.min(ttlMillis, hint.ttlMillis);
            if ("private".equals(hint.scope)) scope = "private";
        }

        private McpToolCatalog toCatalog(List<McpToolDefinition> tools) {
            if (!recorded) return McpToolCatalog.uncached(tools);
            return new McpToolCatalog(
                tools, ttlMillis, scope, System.currentTimeMillis()
            );
        }
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
        private McpProtocolMode protocolMode = McpProtocolMode.AUTO;
        private String clientName = "agent-sdk";
        private String clientVersion = "0.1.0-SNAPSHOT";
        private String clientCapabilitiesJson = "{}";
        private McpInputHandler inputHandler;
        private int maxInputRounds = 4;

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
            this.protocolMode = LATEST_PROTOCOL_VERSION.equals(preferred)
                ? McpProtocolMode.STATELESS : McpProtocolMode.LEGACY;
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

        public Builder protocolMode(McpProtocolMode mode) {
            this.protocolMode = java.util.Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder clientInfo(String name, String version) {
            this.clientName = requireText(name, "client name");
            this.clientVersion = requireText(version, "client version");
            return this;
        }

        public Builder clientCapabilities(String capabilitiesJson) {
            this.clientCapabilitiesJson = requireText(
                capabilitiesJson, "clientCapabilities"
            );
            return this;
        }

        public Builder inputHandler(McpInputHandler handler) {
            this.inputHandler = java.util.Objects.requireNonNull(
                handler, "handler"
            );
            return this;
        }

        public Builder maxInputRounds(int maxInputRounds) {
            if (maxInputRounds < 1) {
                throw new IllegalArgumentException(
                    "maxInputRounds must be positive"
                );
            }
            this.maxInputRounds = maxInputRounds;
            return this;
        }

        public StdioMcpClient build() {
            if (protocolMode == McpProtocolMode.STATELESS
                    && !supportedProtocolVersions.contains(
                        LATEST_PROTOCOL_VERSION)) {
                throw new IllegalArgumentException(
                    "STATELESS mode requires protocol "
                        + LATEST_PROTOCOL_VERSION
                );
            }
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
