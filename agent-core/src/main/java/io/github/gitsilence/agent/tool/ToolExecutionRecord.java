package io.github.gitsilence.agent.tool;

import io.github.gitsilence.agent.model.ToolCall;

import java.time.Instant;
import java.util.Objects;

public final class ToolExecutionRecord {

    private final ToolCall call;
    private final ToolCall executedCall;
    private final ToolResult result;
    private final Instant startedAt;
    private final Instant completedAt;

    public ToolExecutionRecord(ToolCall call,
                               ToolResult result,
                               Instant startedAt,
                               Instant completedAt) {
        this(call, call, result, startedAt, completedAt);
    }

    public ToolExecutionRecord(ToolCall call,
                               ToolCall executedCall,
                               ToolResult result,
                               Instant startedAt,
                               Instant completedAt) {
        this.call = Objects.requireNonNull(call, "call");
        this.executedCall = Objects.requireNonNull(executedCall, "executedCall");
        this.result = Objects.requireNonNull(result, "result");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    public ToolCall getCall() {
        return call;
    }

    /** The call after ToolInterceptor transformations. */
    public ToolCall getExecutedCall() {
        return executedCall;
    }

    public ToolResult getResult() {
        return result;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
