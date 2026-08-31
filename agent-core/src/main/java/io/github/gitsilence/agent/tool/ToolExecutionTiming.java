package io.github.gitsilence.agent.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次 Tool Call 从进入调度队列到执行完成的不可变计时结果。
 *
 * <p>{@code dispatchedAt -> startedAt} 表示等待并行/独占门控的时间，
 * {@code startedAt -> completedAt} 表示拦截器和 Tool 处理时间。SDK 内部使用
 * {@link System#nanoTime()} 计算耗时，避免系统时钟回拨影响；公开构造器则用于兼容
 * 外部创建的执行记录，并根据墙上时钟推导耗时。</p>
 */
public final class ToolExecutionTiming {

    private final Instant dispatchedAt;
    private final Instant startedAt;
    private final Instant completedAt;
    private final long dispatchDurationNanos;
    private final long handlerDurationNanos;
    private final long totalDurationNanos;

    /**
     * 根据三个有序时间点创建计时结果。
     *
     * @throws IllegalArgumentException 当时间点不是非递减顺序时
     */
    public ToolExecutionTiming(Instant dispatchedAt,
                               Instant startedAt,
                               Instant completedAt) {
        this(
            requireOrdered(dispatchedAt, startedAt, completedAt),
            startedAt,
            completedAt,
            durationNanos(dispatchedAt, startedAt),
            durationNanos(startedAt, completedAt),
            durationNanos(dispatchedAt, completedAt)
        );
    }

    private ToolExecutionTiming(Instant dispatchedAt,
                                Instant startedAt,
                                Instant completedAt,
                                long dispatchDurationNanos,
                                long handlerDurationNanos,
                                long totalDurationNanos) {
        this.dispatchedAt = Objects.requireNonNull(
            dispatchedAt, "dispatchedAt"
        );
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.dispatchDurationNanos = nonNegative(
            dispatchDurationNanos, "dispatchDurationNanos"
        );
        this.handlerDurationNanos = nonNegative(
            handlerDurationNanos, "handlerDurationNanos"
        );
        this.totalDurationNanos = nonNegative(
            totalDurationNanos, "totalDurationNanos"
        );
        if (this.dispatchDurationNanos > this.totalDurationNanos
                || this.handlerDurationNanos > this.totalDurationNanos) {
            throw new IllegalArgumentException(
                "partial Tool durations must not exceed totalDurationNanos"
            );
        }
    }

    static ToolExecutionTiming measured(Instant dispatchedAt,
                                        Instant startedAt,
                                        Instant completedAt,
                                        long dispatchDurationNanos,
                                        long handlerDurationNanos,
                                        long totalDurationNanos) {
        requireOrdered(dispatchedAt, startedAt, completedAt);
        return new ToolExecutionTiming(
            dispatchedAt,
            startedAt,
            completedAt,
            dispatchDurationNanos,
            handlerDurationNanos,
            totalDurationNanos
        );
    }

    /** 返回调用进入批次调度器的墙上时间。 */
    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    /** 返回调用通过并行/独占门控并开始执行的墙上时间。 */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** 返回调用完成的墙上时间。 */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /** 返回进入调度器到开始执行之间的单调时钟耗时。 */
    public long getDispatchDurationNanos() {
        return dispatchDurationNanos;
    }

    /** 返回拦截器链和 Tool 实际处理的单调时钟耗时。 */
    public long getHandlerDurationNanos() {
        return handlerDurationNanos;
    }

    /** 返回调度等待与实际处理合计的单调时钟耗时。 */
    public long getTotalDurationNanos() {
        return totalDurationNanos;
    }

    private static Instant requireOrdered(Instant dispatchedAt,
                                          Instant startedAt,
                                          Instant completedAt) {
        Objects.requireNonNull(dispatchedAt, "dispatchedAt");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (startedAt.isBefore(dispatchedAt)
                || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                "Tool execution timestamps must be in non-decreasing order"
            );
        }
        return dispatchedAt;
    }

    private static long durationNanos(Instant start, Instant end) {
        try {
            return Duration.between(start, end).toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long nonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
