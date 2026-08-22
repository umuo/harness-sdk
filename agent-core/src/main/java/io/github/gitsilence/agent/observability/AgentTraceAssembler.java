package io.github.gitsilence.agent.observability;

import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.Usage;
import io.github.gitsilence.agent.runtime.AgentEvent;
import io.github.gitsilence.agent.runtime.AgentEventType;
import io.github.gitsilence.agent.runtime.ExecutionStatus;
import io.github.gitsilence.agent.state.AgentStateSnapshot;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;
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
        if (captureContent) {
            span.attributes.put(
                "agent.model.input.messages",
                limit(renderMessages(request.getMessages()))
            );
        }
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
        if (captureContent) {
            span.attributes.put(
                "agent.model.output.message", limit(renderMessage(assistant))
            );
        }
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
        if (captureContent) {
            span.attributes.put(
                "agent.tool.arguments", limit(call.getArguments())
            );
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
        ToolErrorInfo errorInfo = result.getErrorInfo();
        if (errorInfo != null) {
            span.attributes.put("agent.tool.error.code", errorInfo.getCode());
            span.attributes.put(
                "agent.tool.error.retryable", errorInfo.isRetryable()
            );
        }
        if (captureContent) {
            span.attributes.put(
                "agent.tool.result", limit(result.getContent())
            );
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
        for (MutableSpan span : spans.values()) {
            if (!span.finished) {
                span.finish(event.getTimestamp(), terminalStatus, error);
            }
        }
        MutableSpan turn = spans.get(spanId("turn", turnId));
        if (turn != null) {
            turn.attributes.put("agent.status", status.name());
            if (state != null && state.getStopReason() != null) {
                turn.attributes.put("agent.stop.reason", state.getStopReason());
            }
            if (captureContent && state != null
                    && state.getFinalOutput() != null) {
                turn.attributes.put(
                    "agent.output", limit(state.getFinalOutput())
                );
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

    private static String renderMessages(List<ChatMessage> messages) {
        StringBuilder output = new StringBuilder();
        for (ChatMessage message : messages) {
            if (output.length() > 0) output.append('\n');
            output.append(renderMessage(message));
        }
        return output.toString();
    }

    private static String renderMessage(ChatMessage message) {
        StringBuilder output = new StringBuilder()
            .append(message.getRole().name().toLowerCase())
            .append(':');
        if (message.getContent() != null) {
            output.append(message.getContent());
        }
        for (ToolCall call : message.getToolCalls()) {
            output.append("\n[tool_call name=")
                .append(call.getName())
                .append(" id=")
                .append(call.getId())
                .append(" arguments=")
                .append(call.getArguments())
                .append(']');
        }
        return output.toString();
    }

    private static String abbreviate(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 15) + "...[truncated]";
    }
}
