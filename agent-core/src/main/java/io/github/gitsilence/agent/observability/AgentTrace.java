package io.github.gitsilence.agent.observability;

import io.github.gitsilence.agent.model.Usage;
import io.github.gitsilence.agent.runtime.ExecutionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One completed Agent Turn trace segment with flattened hierarchical spans. */
public final class AgentTrace {

    private final String traceId;
    private final String turnId;
    private final String parentTurnId;
    private final String parentSpanId;
    private final String agentName;
    private final ExecutionStatus status;
    private final Instant startedAt;
    private final Instant endedAt;
    private final long durationNanos;
    private final List<AgentSpan> spans;
    private final Usage usage;
    private final int stepCount;
    private final int modelCallCount;
    private final int toolCallCount;
    private final int toolErrorCount;
    private final long modelStreamEventCount;
    private final Map<String, Object> attributes;
    private final String errorType;
    private final String errorMessage;

    AgentTrace(String traceId,
               String turnId,
               String parentTurnId,
               String parentSpanId,
               String agentName,
               ExecutionStatus status,
               Instant startedAt,
               Instant endedAt,
               long durationNanos,
               List<AgentSpan> spans,
               Usage usage,
               int stepCount,
               int modelCallCount,
               int toolCallCount,
               int toolErrorCount,
               long modelStreamEventCount,
               Map<String, Object> attributes,
               String errorType,
               String errorMessage) {
        this.traceId = requireText(traceId, "traceId");
        this.turnId = requireText(turnId, "turnId");
        this.parentTurnId = optional(parentTurnId);
        this.parentSpanId = optional(parentSpanId);
        this.agentName = requireText(agentName, "agentName");
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.endedAt = Objects.requireNonNull(endedAt, "endedAt");
        this.durationNanos = Math.max(0L, durationNanos);
        this.spans = Collections.unmodifiableList(
            new ArrayList<AgentSpan>(Objects.requireNonNull(spans, "spans"))
        );
        this.usage = Objects.requireNonNull(usage, "usage");
        this.stepCount = stepCount;
        this.modelCallCount = modelCallCount;
        this.toolCallCount = toolCallCount;
        this.toolErrorCount = toolErrorCount;
        this.modelStreamEventCount = modelStreamEventCount;
        this.attributes = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(Objects.requireNonNull(
                attributes, "attributes"
            ))
        );
        this.errorType = optional(errorType);
        this.errorMessage = optional(errorMessage);
    }

    public String getTraceId() { return traceId; }
    public String getTurnId() { return turnId; }
    public String getRunId() { return turnId; }
    public String getParentTurnId() { return parentTurnId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getAgentName() { return agentName; }
    public ExecutionStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public long getDurationNanos() { return durationNanos; }
    public List<AgentSpan> getSpans() { return spans; }
    public Usage getUsage() { return usage; }
    public int getStepCount() { return stepCount; }
    public int getModelCallCount() { return modelCallCount; }
    public int getToolCallCount() { return toolCallCount; }
    public int getToolErrorCount() { return toolErrorCount; }
    public long getModelStreamEventCount() { return modelStreamEventCount; }
    public Map<String, Object> getAttributes() { return attributes; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
