package io.github.gitsilence.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.model.ModelExchange;
import io.github.gitsilence.agent.model.ModelExchangeException;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.Usage;
import io.github.gitsilence.agent.runtime.AgentEvent;
import io.github.gitsilence.agent.runtime.AgentEventType;
import io.github.gitsilence.agent.runtime.ExecutionStatus;
import io.github.gitsilence.agent.state.AgentStateSnapshot;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;
import io.github.gitsilence.agent.tool.ToolOutputReference;
import io.github.gitsilence.agent.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Internal event reducer for one Turn. */
final class AgentTraceAssembler {

    private static final ObjectMapper CONTENT_MAPPER = new ObjectMapper();
    private static final int MAX_CAPTURED_MESSAGES = 200;
    private static final int MAX_CAPTURED_TOOLS = 100;
    private static final int MAX_CAPTURED_TOOL_CALLS = 100;
    private static final int MAX_CAPTURED_JSON_ENTRIES = 500;
    private static final int MAX_CAPTURED_JSON_DEPTH = 24;

    private final String traceId;
    private final String turnId;
    private final String parentTurnId;
    private final String parentSpanId;
    private final String agentName;
    private final boolean captureContent;
    private final int maxContentCharacters;
    private final Instant startedAt;
    private final long startedNanos;
    private final Map<String, Object> traceAttributes =
        new LinkedHashMap<String, Object>();
    private final Map<String, MutableSpan> spans =
        new LinkedHashMap<String, MutableSpan>();
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private long streamEvents;
    private boolean finished;

    AgentTraceAssembler(AgentEvent event,
                        boolean captureContent,
                        int maxContentCharacters,
                        Map<String, Object> commonAttributes) {
        AgentStateSnapshot state = Objects.requireNonNull(
            event.getState(), "TURN_STARTED state"
        );
        Map<String, Object> metadata = state.getMetadata();
        this.traceId = string(metadata.get("traceId"), event.getTurnId());
        this.turnId = event.getTurnId();
        this.parentTurnId = string(metadata.get("parentTurnId"), "");
        String parentToolCallId = string(
            metadata.get("parentToolCallId"), ""
        );
        this.parentSpanId = parentTurnId.isEmpty()
            || parentToolCallId.isEmpty()
            ? "" : spanId("tool", parentTurnId, parentToolCallId);
        this.agentName = event.getAgentName();
        this.captureContent = captureContent;
        this.maxContentCharacters = maxContentCharacters;
        this.startedAt = event.getTimestamp();
        this.startedNanos = System.nanoTime();
        this.traceAttributes.putAll(commonAttributes);
        traceAttributes.put("agent.name", agentName);
        traceAttributes.put("agent.turn.id", turnId);
        Object path = metadata.get("invocationPath");
        if (path != null) {
            traceAttributes.put(
                "agent.invocation.path", String.valueOf(path)
            );
        }
        if (!parentTurnId.isEmpty()) {
            traceAttributes.put("agent.parent.turn.id", parentTurnId);
        }

        MutableSpan turn = new MutableSpan(
            traceId,
            spanId("turn", turnId),
            parentSpanId,
            "agent.turn " + agentName,
            AgentSpanKind.TURN,
            event.getTimestamp()
        );
        turn.attributes.putAll(traceAttributes);
        turn.attributes.put("agent.content.captured", captureContent);
        turn.input.put("messageCount", state.getMessages().size());
        if (captureContent) {
            captureMessages(turn.input, state.getMessages());
        }
        spans.put(turn.spanId, turn);
    }

    synchronized AgentTrace accept(AgentEvent event) {
        if (finished) return null;
        switch (event.getType()) {
            case TURN_STARTED:
                return null;
            case STEP_STARTED:
                startStep(event);
                return null;
            case MODEL_STARTED:
                startModel(event);
                return null;
            case MODEL_STREAM_EVENT:
                streamEvents++;
                return null;
            case MODEL_COMPLETED:
                completeModel(event);
                return null;
            case TOOL_STARTED:
                startTool(event);
                return null;
            case TOOL_COMPLETED:
                completeTool(event);
                return null;
            case STEP_COMPLETED:
                completeStep(event);
                return null;
            case TURN_COMPLETED:
            case TURN_STOPPED:
            case TURN_FAILED:
            case TURN_CANCELLED:
                return finish(event);
            default:
                return null;
        }
    }

    private void startStep(AgentEvent event) {
        String id = stepSpanId(turnId, event.getStep());
        MutableSpan span = new MutableSpan(
            traceId,
            id,
            spanId("turn", turnId),
            "agent.step " + event.getStep(),
            AgentSpanKind.STEP,
            event.getTimestamp()
        );
        span.attributes.put("agent.step", event.getStep());
        span.input.put("step", event.getStep());
        spans.put(id, span);
    }

    private void startModel(AgentEvent event) {
        String id = modelSpanId(turnId, event.getStep());
        MutableSpan span = new MutableSpan(
            traceId,
            id,
            stepSpanId(turnId, event.getStep()),
            "agent.model",
            AgentSpanKind.MODEL,
            event.getTimestamp()
        );
        ModelRequest request = event.getModelRequest();
        span.attributes.put(
            "agent.model.input.message_count", request.getMessages().size()
        );
        span.attributes.put(
            "agent.model.available_tool_count", request.getTools().size()
        );
        putPositive(
            span.attributes,
            "agent.model.input.omitted_message_count",
            request.getMessages().size() - MAX_CAPTURED_MESSAGES
        );
        putPositive(
            span.attributes,
            "agent.model.input.omitted_tool_count",
            request.getTools().size() - MAX_CAPTURED_TOOLS
        );
        putPositive(
            span.attributes,
            "agent.model.input.omitted_tool_call_count",
            omittedToolCalls(request.getMessages())
        );
        span.attributes.put("agent.content.captured", captureContent);
        span.input.putAll(modelInput(request));
        spans.put(id, span);
    }

    private void completeModel(AgentEvent event) {
        MutableSpan span = modelSpan(event);
        ModelResponse response = event.getModelResponse();
        Usage usage = response.getUsage();
        if (usage != null) {
            inputTokens += usage.getInputTokens();
            outputTokens += usage.getOutputTokens();
            totalTokens += usage.getTotalTokens();
            span.attributes.put(
                "agent.model.usage.input_tokens", usage.getInputTokens()
            );
            span.attributes.put(
                "agent.model.usage.output_tokens", usage.getOutputTokens()
            );
            span.attributes.put(
                "agent.model.usage.total_tokens", usage.getTotalTokens()
            );
        }
        ChatMessage assistant = response.getAssistantMessage();
        span.attributes.put(
            "agent.model.output.tool_call_count",
            assistant.getToolCalls().size()
        );
        putPositive(
            span.attributes,
            "agent.model.output.omitted_tool_call_count",
            assistant.getToolCalls().size() - MAX_CAPTURED_TOOL_CALLS
        );
        if (usage != null) {
            span.output.put("usage", usage(usage));
        }
        if (captureContent) {
            span.output.put("message", message(assistant));
            if (!response.getMetadata().isEmpty()) {
                span.output.put("metadata", response.getMetadata());
            }
        }
        applyModelExchange(span, response.getExchange());
        span.finish(event.getTimestamp(), AgentSpanStatus.OK, null);
    }

    private MutableSpan modelSpan(AgentEvent event) {
        String id = modelSpanId(turnId, event.getStep());
        MutableSpan existing = spans.get(id);
        if (existing != null) return existing;
        MutableSpan span = new MutableSpan(
            traceId,
            id,
            stepSpanId(turnId, event.getStep()),
            "agent.model",
            AgentSpanKind.MODEL,
            event.getTimestamp()
        );
        spans.put(id, span);
        return span;
    }

    private void startTool(AgentEvent event) {
        ToolCall call = event.getToolCall();
        String id = toolSpanId(turnId, call.getId());
        MutableSpan span = new MutableSpan(
            traceId,
            id,
            stepSpanId(turnId, event.getStep()),
            "agent.tool " + call.getName(),
            AgentSpanKind.TOOL,
            event.getTimestamp()
        );
        span.attributes.put("agent.tool.name", call.getName());
        span.attributes.put("agent.tool.call.id", call.getId());
        span.attributes.put(
            "agent.tool.argument.characters", call.getArguments().length()
        );
        span.attributes.put("agent.content.captured", captureContent);
        span.input.put("name", call.getName());
        span.input.put("callId", call.getId());
        if (captureContent) {
            span.input.put("arguments", jsonOrText(call.getArguments()));
        }
        spans.put(id, span);
    }

    private void completeTool(AgentEvent event) {
        ToolExecutionRecord record = event.getToolExecution();
        ToolCall call = record.getCall();
        String id = toolSpanId(turnId, call.getId());
        MutableSpan span = spans.get(id);
        if (span == null) {
            startTool(event);
            span = spans.get(id);
        }
        ToolResult result = record.getResult();
        span.attributes.put("agent.tool.error", result.isError());
        span.attributes.put(
            "agent.tool.result.characters", result.getContent().length()
        );
        span.attributes.put(
            "agent.tool.output_reference_count",
            result.getOutputReferences().size()
        );
        span.output.put("error", result.isError());
        span.output.put("characters", result.getContent().length());
        span.output.put("outputReferenceCount", result.getOutputReferences().size());
        ToolErrorInfo errorInfo = result.getErrorInfo();
        if (errorInfo != null) {
            span.attributes.put("agent.tool.error.code", errorInfo.getCode());
            span.attributes.put(
                "agent.tool.error.retryable", errorInfo.isRetryable()
            );
            span.output.put("errorInfo", errorInfo(errorInfo));
        }
        if (captureContent) {
            span.output.put("content", limit(result.getContent()));
            if (!result.getMetadata().isEmpty()) {
                span.output.put("metadata", result.getMetadata());
            }
            if (!result.getOutputReferences().isEmpty()) {
                span.output.put(
                    "outputReferences",
                    outputReferences(result.getOutputReferences())
                );
            }
        }
        if (result.isError()) {
            String errorType = errorInfo == null
                ? "tool.error" : "tool." + errorInfo.getCode();
            String errorMessage = errorInfo != null
                ? errorInfo.getMessage()
                : captureContent
                    ? limit(result.getContent())
                    : "Tool returned an error";
            span.finishObserved(
                record.getStartedAt(),
                record.getCompletedAt(),
                AgentSpanStatus.ERROR,
                errorType,
                errorMessage
            );
        } else {
            span.finishObserved(
                record.getStartedAt(),
                record.getCompletedAt(),
                AgentSpanStatus.OK,
                "",
                ""
            );
        }
    }

    private void completeStep(AgentEvent event) {
        MutableSpan span = spans.get(stepSpanId(turnId, event.getStep()));
        if (span != null) {
            span.output.put("status", AgentSpanStatus.OK.name());
            span.finish(event.getTimestamp(), AgentSpanStatus.OK, null);
        }
    }

    private AgentTrace finish(AgentEvent event) {
        finished = true;
        AgentStateSnapshot state = event.getState();
        ExecutionStatus status = state == null
            ? status(event.getType()) : state.getStatus();
        AgentSpanStatus terminalStatus = spanStatus(status);
        Throwable error = event.getError();
        ModelExchange failedExchange = modelExchange(error);
        if (failedExchange != null) {
            for (MutableSpan span : spans.values()) {
                if (span.kind == AgentSpanKind.MODEL && !span.finished) {
                    applyModelExchange(span, failedExchange);
                }
            }
        }
        for (MutableSpan span : spans.values()) {
            if (!span.finished) {
                span.finish(event.getTimestamp(), terminalStatus, error);
            }
        }
        MutableSpan turn = spans.get(spanId("turn", turnId));
        if (turn != null) {
            turn.attributes.put("agent.status", status.name());
            turn.output.put("status", status.name());
            if (state != null && state.getStopReason() != null) {
                turn.attributes.put("agent.stop.reason", state.getStopReason());
                turn.output.put("stopReason", state.getStopReason());
            }
            if (captureContent && state != null
                    && state.getFinalOutput() != null) {
                turn.output.put("finalOutput", limit(state.getFinalOutput()));
            }
        }

        List<AgentSpan> completed = new ArrayList<AgentSpan>();
        int steps = 0;
        int models = 0;
        int tools = 0;
        int toolErrors = 0;
        for (MutableSpan span : spans.values()) {
            AgentSpan immutable = span.toSpan(maxContentCharacters);
            completed.add(immutable);
            if (immutable.getKind() == AgentSpanKind.STEP) steps++;
            if (immutable.getKind() == AgentSpanKind.MODEL) models++;
            if (immutable.getKind() == AgentSpanKind.TOOL) {
                tools++;
                if (immutable.getStatus() == AgentSpanStatus.ERROR) {
                    toolErrors++;
                }
            }
        }
        String errorType = error == null ? "" : error.getClass().getName();
        String errorMessage = error == null ? "" : limit(error.getMessage());
        return new AgentTrace(
            traceId,
            turnId,
            parentTurnId,
            parentSpanId,
            agentName,
            status,
            startedAt,
            event.getTimestamp(),
            Math.max(0L, System.nanoTime() - startedNanos),
            completed,
            new Usage(inputTokens, outputTokens, totalTokens),
            steps,
            models,
            tools,
            toolErrors,
            streamEvents,
            traceAttributes,
            errorType,
            errorMessage
        );
    }

    private Map<String, Object> modelInput(ModelRequest request) {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        ModelOptions options = request.getOptions();
        Map<String, Object> capturedOptions =
            new LinkedHashMap<String, Object>();
        if (options.getTemperature() != null) {
            capturedOptions.put("temperature", options.getTemperature());
        }
        if (options.getMaxTokens() != null) {
            capturedOptions.put("maxTokens", options.getMaxTokens());
        }
        if (captureContent && !options.getExtensions().isEmpty()) {
            capturedOptions.put("extensions", options.getExtensions());
        }
        if (!capturedOptions.isEmpty()) {
            input.put("options", capturedOptions);
        }
        if (!captureContent) return input;

        captureMessages(input, request.getMessages());
        List<Map<String, Object>> tools =
            new ArrayList<Map<String, Object>>();
        int toolLimit = Math.min(request.getTools().size(), MAX_CAPTURED_TOOLS);
        for (int index = 0; index < toolLimit; index++) {
            tools.add(tool(request.getTools().get(index)));
        }
        input.put("tools", tools);
        return input;
    }

    private void applyModelExchange(MutableSpan span,
                                    ModelExchange exchange) {
        if (exchange == null) return;
        span.attributes.put("agent.model.provider.name", exchange.getProvider());
        span.attributes.put(
            "agent.model.provider.endpoint", exchange.getEndpoint()
        );
        span.attributes.put(
            "agent.model.provider.streaming", exchange.isStreaming()
        );
        span.attributes.put(
            "agent.model.provider.response.media_type",
            exchange.getResponseMediaType()
        );
        if (exchange.getResponseStatus() > 0) {
            span.attributes.put(
                "agent.model.provider.response.status",
                exchange.getResponseStatus()
            );
        }
        if (!captureContent) return;

        span.sdkInput.putAll(span.input);
        span.sdkOutput.putAll(span.output);
        span.input.clear();
        span.output.clear();

        boolean requestTruncated = captureProviderBody(
            span.input,
            exchange.getRequestBody(),
            "application/json"
        );
        boolean responseTruncated = captureProviderBody(
            span.output,
            exchange.getResponseBody(),
            exchange.getResponseMediaType()
        );
        span.attributes.put("agent.model.provider.exchange.captured", true);
        if (requestTruncated) {
            span.attributes.put(
                "agent.model.provider.request.truncated", true
            );
        }
        if (responseTruncated || exchange.isResponseTruncated()) {
            span.attributes.put(
                "agent.model.provider.response.truncated", true
            );
        }
    }

    private boolean captureProviderBody(Map<String, Object> target,
                                        String body,
                                        String mediaType) {
        if (body == null || body.isEmpty()) return false;
        boolean[] truncated = { false };
        if (mediaType != null && mediaType.contains("json")) {
            try {
                Object parsed = CONTENT_MAPPER.readValue(body, Object.class);
                Object bounded = boundedJson(parsed, 0, truncated);
                if (bounded instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> values =
                        (Map<String, Object>) bounded;
                    target.putAll(values);
                } else {
                    target.put("body", bounded);
                }
                return truncated[0];
            } catch (Exception ignored) {
                // Preserve non-JSON error bodies as bounded text.
            }
        }
        String limited = limit(body);
        target.put("body", limited);
        return !limited.equals(body);
    }

    private Object boundedJson(Object value,
                               int depth,
                               boolean[] truncated) {
        if (value == null || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String) {
            String limited = limit((String) value);
            if (!limited.equals(value)) truncated[0] = true;
            return limited;
        }
        if (depth >= MAX_CAPTURED_JSON_DEPTH) {
            truncated[0] = true;
            return "...[max depth]";
        }
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> result =
                new LinkedHashMap<String, Object>();
            int captured = 0;
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (captured++ >= MAX_CAPTURED_JSON_ENTRIES) {
                    truncated[0] = true;
                    break;
                }
                result.put(
                    String.valueOf(entry.getKey()),
                    boundedJson(entry.getValue(), depth + 1, truncated)
                );
            }
            return result;
        }
        if (value instanceof List) {
            List<?> source = (List<?>) value;
            List<Object> result = new ArrayList<Object>();
            int limit = Math.min(source.size(), MAX_CAPTURED_JSON_ENTRIES);
            for (int index = 0; index < limit; index++) {
                result.add(boundedJson(
                    source.get(index), depth + 1, truncated
                ));
            }
            if (source.size() > limit) truncated[0] = true;
            return result;
        }
        String limited = limit(String.valueOf(value));
        if (!limited.equals(String.valueOf(value))) truncated[0] = true;
        return limited;
    }

    private ModelExchange modelExchange(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof ModelExchangeException) {
                return ((ModelExchangeException) current).getExchange();
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return null;
    }

    private void captureMessages(Map<String, Object> target,
                                 List<ChatMessage> messages) {
        List<Map<String, Object>> captured =
            new ArrayList<Map<String, Object>>();
        int messageLimit = Math.min(messages.size(), MAX_CAPTURED_MESSAGES);
        for (int index = 0; index < messageLimit; index++) {
            captured.add(message(messages.get(index)));
        }
        target.put("messages", captured);
    }

    private Map<String, Object> message(ChatMessage message) {
        Map<String, Object> captured = new LinkedHashMap<String, Object>();
        captured.put("role", message.getRole().name().toLowerCase());
        if (message.getContent() != null) {
            captured.put("content", limit(message.getContent()));
        }
        if (message.getToolCallId() != null) {
            captured.put("toolCallId", message.getToolCallId());
        }
        if (message.getToolName() != null) {
            captured.put("toolName", message.getToolName());
        }
        if (message.isError()) {
            captured.put("error", true);
        }
        if (!message.getToolCalls().isEmpty()) {
            List<Map<String, Object>> calls =
                new ArrayList<Map<String, Object>>();
            int callLimit = Math.min(
                message.getToolCalls().size(), MAX_CAPTURED_TOOL_CALLS
            );
            for (int index = 0; index < callLimit; index++) {
                calls.add(toolCall(message.getToolCalls().get(index)));
            }
            captured.put("toolCalls", calls);
        }
        return captured;
    }

    private int omittedToolCalls(List<ChatMessage> messages) {
        int omitted = 0;
        for (ChatMessage message : messages) {
            omitted += Math.max(
                0,
                message.getToolCalls().size() - MAX_CAPTURED_TOOL_CALLS
            );
        }
        return omitted;
    }

    private void putPositive(Map<String, Object> target,
                             String name,
                             int value) {
        if (value > 0) target.put(name, value);
    }

    private Map<String, Object> toolCall(ToolCall call) {
        Map<String, Object> captured = new LinkedHashMap<String, Object>();
        captured.put("id", call.getId());
        captured.put("name", call.getName());
        captured.put("arguments", jsonOrText(call.getArguments()));
        return captured;
    }

    private Map<String, Object> tool(ToolDefinition definition) {
        Map<String, Object> captured = new LinkedHashMap<String, Object>();
        captured.put("name", definition.getName());
        captured.put("description", limit(definition.getDescription()));
        captured.put("inputSchema", jsonOrText(definition.getInputSchema()));
        if (!definition.getMetadata().isEmpty()) {
            captured.put("metadata", definition.getMetadata());
        }
        return captured;
    }

    private Map<String, Object> usage(Usage usage) {
        Map<String, Object> captured = new LinkedHashMap<String, Object>();
        captured.put("inputTokens", usage.getInputTokens());
        captured.put("outputTokens", usage.getOutputTokens());
        captured.put("totalTokens", usage.getTotalTokens());
        return captured;
    }

    private Map<String, Object> errorInfo(ToolErrorInfo error) {
        Map<String, Object> captured = new LinkedHashMap<String, Object>();
        captured.put("code", error.getCode());
        captured.put("retryable", error.isRetryable());
        if (captureContent) {
            captured.put("message", limit(error.getMessage()));
            if (error.getRecoveryHint() != null) {
                captured.put("recoveryHint", limit(error.getRecoveryHint()));
            }
            if (!error.getDetails().isEmpty()) {
                captured.put("details", error.getDetails());
            }
        }
        return captured;
    }

    private List<Map<String, Object>> outputReferences(
            List<ToolOutputReference> references) {
        List<Map<String, Object>> captured =
            new ArrayList<Map<String, Object>>();
        for (ToolOutputReference reference : references) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("kind", reference.getKind().name());
            value.put("path", limit(reference.getPath()));
            if (!reference.getInstruction().isEmpty()) {
                value.put("instruction", limit(reference.getInstruction()));
            }
            captured.add(value);
        }
        return captured;
    }

    private Object jsonOrText(String value) {
        String limited = limit(value);
        if (!limited.equals(value)) return limited;
        try {
            return CONTENT_MAPPER.readValue(value, Object.class);
        } catch (Exception ignored) {
            return limited;
        }
    }

    private String limit(String value) {
        if (value == null) return "";
        if (value.length() <= maxContentCharacters) return value;
        return value.substring(0, maxContentCharacters - 15)
            + "...[truncated]";
    }

    private static final class MutableSpan {
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final String name;
        private final AgentSpanKind kind;
        private Instant startedAt;
        private final long startedNanos = System.nanoTime();
        private final Map<String, Object> attributes =
            new LinkedHashMap<String, Object>();
        private final Map<String, Object> input =
            new LinkedHashMap<String, Object>();
        private final Map<String, Object> output =
            new LinkedHashMap<String, Object>();
        private final Map<String, Object> sdkInput =
            new LinkedHashMap<String, Object>();
        private final Map<String, Object> sdkOutput =
            new LinkedHashMap<String, Object>();
        private Instant endedAt;
        private long durationNanos;
        private AgentSpanStatus status;
        private String errorType = "";
        private String errorMessage = "";
        private boolean finished;

        private MutableSpan(String traceId,
                            String spanId,
                            String parentSpanId,
                            String name,
                            AgentSpanKind kind,
                            Instant startedAt) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.name = name;
            this.kind = kind;
            this.startedAt = startedAt;
        }

        private void finish(Instant endedAt,
                            AgentSpanStatus status,
                            Throwable error) {
            if (finished) return;
            this.finished = true;
            this.endedAt = endedAt;
            this.durationNanos = Math.max(0L, System.nanoTime() - startedNanos);
            this.status = status;
            if (error != null) {
                this.errorType = error.getClass().getName();
                this.errorMessage = error.getMessage() == null
                    ? "" : error.getMessage();
            }
        }

        private void finishObserved(Instant observedStartedAt,
                                    Instant observedEndedAt,
                                    AgentSpanStatus status,
                                    String errorType,
                                    String errorMessage) {
            if (finished) return;
            this.finished = true;
            this.startedAt = observedStartedAt;
            this.endedAt = observedEndedAt;
            this.durationNanos = durationNanos(
                observedStartedAt, observedEndedAt
            );
            this.status = status;
            this.errorType = errorType == null ? "" : errorType;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }

        private AgentSpan toSpan(int maxErrorCharacters) {
            return new AgentSpan(
                traceId,
                spanId,
                parentSpanId,
                name,
                kind,
                status == null ? AgentSpanStatus.ERROR : status,
                startedAt,
                endedAt == null ? startedAt : endedAt,
                durationNanos,
                input,
                output,
                sdkInput,
                sdkOutput,
                attributes,
                errorType,
                abbreviate(errorMessage, maxErrorCharacters)
            );
        }

        private static long durationNanos(Instant startedAt, Instant endedAt) {
            try {
                return Math.max(
                    0L, Duration.between(startedAt, endedAt).toNanos()
                );
            } catch (ArithmeticException ignored) {
                return 0L;
            }
        }
    }

    private static ExecutionStatus status(AgentEventType type) {
        if (type == AgentEventType.TURN_COMPLETED) {
            return ExecutionStatus.COMPLETED;
        }
        if (type == AgentEventType.TURN_STOPPED) {
            return ExecutionStatus.STOPPED;
        }
        if (type == AgentEventType.TURN_CANCELLED) {
            return ExecutionStatus.CANCELLED;
        }
        return ExecutionStatus.FAILED;
    }

    private static AgentSpanStatus spanStatus(ExecutionStatus status) {
        if (status == ExecutionStatus.COMPLETED) return AgentSpanStatus.OK;
        if (status == ExecutionStatus.STOPPED) return AgentSpanStatus.STOPPED;
        if (status == ExecutionStatus.CANCELLED) {
            return AgentSpanStatus.CANCELLED;
        }
        return AgentSpanStatus.ERROR;
    }

    private static String stepSpanId(String turnId, int step) {
        return spanId("step", turnId, Integer.toString(step));
    }

    private static String modelSpanId(String turnId, int step) {
        return spanId("model", turnId, Integer.toString(step));
    }

    private static String toolSpanId(String turnId, String callId) {
        return spanId("tool", turnId, callId);
    }

    private static String spanId(String... parts) {
        StringBuilder value = new StringBuilder("agent-sdk");
        for (String part : parts) {
            value.append('\u0000').append(part == null ? "" : part);
        }
        return UUID.nameUUIDFromBytes(
            value.toString().getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private static String string(Object value, String fallback) {
        return value instanceof String && !((String) value).trim().isEmpty()
            ? (String) value : fallback;
    }

    private static String abbreviate(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 15) + "...[truncated]";
    }
}
