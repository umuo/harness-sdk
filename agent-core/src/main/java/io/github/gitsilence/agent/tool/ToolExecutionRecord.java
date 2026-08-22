package io.github.gitsilence.agent.tool;

import io.github.gitsilence.agent.model.ToolCall;

import java.time.Instant;
import java.util.Objects;

public final class ToolExecutionRecord {

    private final ToolCall call;
    private final ToolResult result;
    private final Instant startedAt;
    private final Instant completedAt;

    public ToolExecutionRecord(ToolCall call,
                               ToolResult result,
                               Instant startedAt,
                               Instant completedAt) {
        this.call = Objects.requireNonNull(call, "call");
        this.result = Objects.requireNonNull(result, "result");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    public ToolCall getCall() {
        return call;
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
