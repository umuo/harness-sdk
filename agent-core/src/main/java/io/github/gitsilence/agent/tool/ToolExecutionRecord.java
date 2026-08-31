package io.github.gitsilence.agent.tool;

import io.github.gitsilence.agent.model.ToolCall;

import java.time.Instant;
import java.util.Objects;

/** 一次 Tool Call 的原始调用、实际调用、结果和分阶段计时记录。 */
public final class ToolExecutionRecord {

    private final ToolCall call;
    private final ToolCall executedCall;
    private final ToolResult result;
    private final ToolExecutionTiming timing;

    public ToolExecutionRecord(ToolCall call,
                               ToolResult result,
                               Instant startedAt,
                               Instant completedAt) {
        this(
            call,
            call,
            result,
            new ToolExecutionTiming(startedAt, startedAt, completedAt)
        );
    }

    public ToolExecutionRecord(ToolCall call,
                               ToolCall executedCall,
                               ToolResult result,
                               Instant startedAt,
                               Instant completedAt) {
        this(
            call,
            executedCall,
            result,
            new ToolExecutionTiming(startedAt, startedAt, completedAt)
        );
    }

    public ToolExecutionRecord(ToolCall call,
                               ToolResult result,
                               ToolExecutionTiming timing) {
        this(call, call, result, timing);
    }

    /** 创建包含拦截器改写结果和分阶段计时的完整记录。 */
    public ToolExecutionRecord(ToolCall call,
                               ToolCall executedCall,
                               ToolResult result,
                               ToolExecutionTiming timing) {
        this.call = Objects.requireNonNull(call, "call");
        this.executedCall = Objects.requireNonNull(executedCall, "executedCall");
        this.result = Objects.requireNonNull(result, "result");
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    public ToolCall getCall() {
        return call;
    }

    /** 返回经过 ToolInterceptor 改写后实际执行的调用。 */
    public ToolCall getExecutedCall() {
        return executedCall;
    }

    public ToolResult getResult() {
        return result;
    }

    /** 返回不可变的分阶段计时对象。 */
    public ToolExecutionTiming getTiming() {
        return timing;
    }

    /** 返回调用进入当前批次调度队列的时间。 */
    public Instant getDispatchedAt() {
        return timing.getDispatchedAt();
    }

    /** 返回通过并行/独占门控并开始执行拦截器链的时间。 */
    public Instant getStartedAt() {
        return timing.getStartedAt();
    }

    public Instant getCompletedAt() {
        return timing.getCompletedAt();
    }

    /** 返回等待调度门控的纳秒数。 */
    public long getDispatchDurationNanos() {
        return timing.getDispatchDurationNanos();
    }

    /** 返回拦截器链与 Tool 实际处理的纳秒数。 */
    public long getHandlerDurationNanos() {
        return timing.getHandlerDurationNanos();
    }

    /** 返回从进入调度器到完成的总纳秒数。 */
    public long getTotalDurationNanos() {
        return timing.getTotalDurationNanos();
    }
}
