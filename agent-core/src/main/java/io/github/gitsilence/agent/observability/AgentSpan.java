package io.github.gitsilence.agent.observability;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** An immutable, exporter-neutral Turn, Step, Model, or Tool span. */
public final class AgentSpan {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    private final AgentSpanKind kind;
    private final AgentSpanStatus status;
    private final Instant startedAt;
    private final Instant endedAt;
    private final long durationNanos;
    private final Map<String, Object> attributes;
    private final String errorType;
    private final String errorMessage;

    AgentSpan(String traceId,
              String spanId,
              String parentSpanId,
              String name,
              AgentSpanKind kind,
              AgentSpanStatus status,
              Instant startedAt,
              Instant endedAt,
              long durationNanos,
              Map<String, Object> attributes,
              String errorType,
              String errorMessage) {
        this.traceId = requireText(traceId, "traceId");
        this.spanId = requireText(spanId, "spanId");
        this.parentSpanId = optional(parentSpanId);
        this.name = requireText(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.endedAt = Objects.requireNonNull(endedAt, "endedAt");
        this.durationNanos = Math.max(0L, durationNanos);
        this.attributes = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(Objects.requireNonNull(
                attributes, "attributes"
            ))
        );
        this.errorType = optional(errorType);
        this.errorMessage = optional(errorMessage);
    }

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getName() { return name; }
    public AgentSpanKind getKind() { return kind; }
    public AgentSpanStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public long getDurationNanos() { return durationNanos; }
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
