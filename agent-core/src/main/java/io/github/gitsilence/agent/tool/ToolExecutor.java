package io.github.gitsilence.agent.tool;

import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.plugin.ToolInterceptor;
import io.github.gitsilence.agent.plugin.ToolInvocation;
import io.github.gitsilence.agent.runtime.Futures;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 一批模型 Tool Call 的执行器。
 *
 * <p>它统一处理工具查找、JSON 参数解析、拦截器链、超时、取消、错误策略和最终
 * 输出限制。并行模式只让显式声明并行安全的连续 Tool Call 同时执行；独占 Tool
 * 会形成顺序屏障，返回记录仍保持模型原始调用顺序。</p>
 */
public final class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolExecutionMode mode;
    private final ToolErrorPolicy errorPolicy;
    private final Duration timeout;
    private final ToolResultPolicy resultPolicy;
    private final List<ToolInterceptor> interceptors;

    public ToolExecutor(ToolRegistry registry,
                        ToolExecutionMode mode,
                        ToolErrorPolicy errorPolicy,
                        Duration timeout) {
        this(registry, mode, errorPolicy, timeout,
            BoundedToolResultPolicy.defaults(),
            Collections.<ToolInterceptor>emptyList());
    }

    public ToolExecutor(ToolRegistry registry,
                        ToolExecutionMode mode,
                        ToolErrorPolicy errorPolicy,
                        Duration timeout,
                        List<ToolInterceptor> interceptors) {
        this(registry, mode, errorPolicy, timeout,
            BoundedToolResultPolicy.defaults(), interceptors);
    }

    public ToolExecutor(ToolRegistry registry,
                        ToolExecutionMode mode,
                        ToolErrorPolicy errorPolicy,
                        Duration timeout,
                        ToolResultPolicy resultPolicy,
                        List<ToolInterceptor> interceptors) {
        this.registry = registry;
        this.mode = mode;
        this.errorPolicy = errorPolicy;
        this.timeout = timeout;
        this.resultPolicy = Objects.requireNonNull(resultPolicy, "resultPolicy");
        this.interceptors = Collections.unmodifiableList(
            new ArrayList<ToolInterceptor>(
                Objects.requireNonNull(interceptors, "interceptors")
            )
        );
    }

    public CompletableFuture<List<ToolExecutionRecord>> executeAll(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory) {
        return executeAll(calls, contextFactory, call -> null);
    }

    public CompletableFuture<List<ToolExecutionRecord>> executeAll(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory,
            Function<ToolCall, ToolInvocation> invocationFactory) {
        if (calls.isEmpty()) {
            return CompletableFuture.completedFuture(
                Collections.<ToolExecutionRecord>emptyList()
            );
        }
        DispatchClock dispatchClock = DispatchClock.capture();
        if (mode == ToolExecutionMode.PARALLEL) {
            return executeParallel(
                calls, contextFactory, invocationFactory, dispatchClock
            );
        }
        return executeSequential(
            calls, contextFactory, invocationFactory, dispatchClock
        );
    }

    private CompletableFuture<List<ToolExecutionRecord>> executeSequential(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory,
            Function<ToolCall, ToolInvocation> invocationFactory,
            DispatchClock dispatchClock) {
        final CompletableFuture<List<ToolExecutionRecord>> result =
            new CompletableFuture<List<ToolExecutionRecord>>();
        // 顺序模式始终只有一个活动 Tool future，取消批次时只需取消它。
        final AtomicReference<CompletableFuture<ToolExecutionRecord>> active =
            new AtomicReference<CompletableFuture<ToolExecutionRecord>>();
        executeSequentialAt(
            calls,
            contextFactory,
            invocationFactory,
            0,
            new ArrayList<ToolExecutionRecord>(),
            active,
            result,
            dispatchClock
        );
        result.whenComplete((records, error) -> {
            if (result.isCancelled()) {
                CompletableFuture<ToolExecutionRecord> current = active.getAndSet(null);
                if (current != null) {
                    current.cancel(true);
                }
            }
        });
        return result;
    }

    private void executeSequentialAt(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory,
            Function<ToolCall, ToolInvocation> invocationFactory,
            int index,
            List<ToolExecutionRecord> records,
            AtomicReference<CompletableFuture<ToolExecutionRecord>> active,
            CompletableFuture<List<ToolExecutionRecord>> result,
            DispatchClock dispatchClock) {
        if (result.isDone()) {
            return;
        }
        if (index >= calls.size()) {
            result.complete(Collections.unmodifiableList(
                new ArrayList<ToolExecutionRecord>(records)
            ));
            return;
        }

        ToolCall call = calls.get(index);
        final CompletableFuture<ToolExecutionRecord> current;
        try {
            current = executeOne(
                call,
                contextFactory.apply(call),
                invocationFactory.apply(call),
                false,
                dispatchClock
            );
        } catch (Throwable error) {
            // 工厂或自定义注册表在回调线程抛错时也必须结束批次，不能留下悬空 future。
            result.completeExceptionally(error);
            return;
        }
        active.set(current);
        if (result.isCancelled()) {
            current.cancel(true);
            return;
        }
        current.whenComplete((record, error) -> {
            active.compareAndSet(current, null);
            if (result.isDone()) {
                return;
            }
            if (error != null) {
                result.completeExceptionally(Futures.unwrap(error));
                return;
            }
            records.add(record);
            executeSequentialAt(
                calls, contextFactory, invocationFactory,
                index + 1, records, active, result, dispatchClock
            );
        });
    }

    private CompletableFuture<List<ToolExecutionRecord>> executeParallel(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory,
            Function<ToolCall, ToolInvocation> invocationFactory,
            DispatchClock dispatchClock) {
        final CompletableFuture<List<ToolExecutionRecord>> result =
            new CompletableFuture<List<ToolExecutionRecord>>();
        final AtomicReference<CompletableFuture<List<ToolExecutionRecord>>> active =
            new AtomicReference<CompletableFuture<List<ToolExecutionRecord>>>();
        executeParallelAt(
            calls,
            contextFactory,
            invocationFactory,
            0,
            new ArrayList<ToolExecutionRecord>(),
            active,
            result,
            dispatchClock
        );
        result.whenComplete((records, error) -> {
            if (result.isCancelled()) {
                CompletableFuture<List<ToolExecutionRecord>> current =
                    active.getAndSet(null);
                if (current != null) {
                    current.cancel(true);
                }
            }
        });
        return result;
    }

    private void executeParallelAt(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory,
            Function<ToolCall, ToolInvocation> invocationFactory,
            int index,
            List<ToolExecutionRecord> records,
            AtomicReference<CompletableFuture<List<ToolExecutionRecord>>> active,
            CompletableFuture<List<ToolExecutionRecord>> result,
            DispatchClock dispatchClock) {
        if (result.isDone()) {
            return;
        }
        if (index >= calls.size()) {
            result.complete(Collections.unmodifiableList(
                new ArrayList<ToolExecutionRecord>(records)
            ));
            return;
        }

        final boolean parallelAdmission;
        final int end;
        try {
            parallelAdmission = supportsParallelToolCalls(calls.get(index));
            int candidate = index + 1;
            if (parallelAdmission) {
                // 连续只读/并行安全调用组成共享阶段；独占调用是阶段之间的屏障。
                while (candidate < calls.size()
                        && supportsParallelToolCalls(calls.get(candidate))) {
                    candidate++;
                }
            }
            end = candidate;
        } catch (Throwable error) {
            result.completeExceptionally(error);
            return;
        }

        List<CompletableFuture<ToolExecutionRecord>> executions =
            new ArrayList<CompletableFuture<ToolExecutionRecord>>(end - index);
        try {
            for (int current = index; current < end; current++) {
                ToolCall call = calls.get(current);
                executions.add(executeOne(
                    call,
                    contextFactory.apply(call),
                    invocationFactory.apply(call),
                    parallelAdmission,
                    dispatchClock
                ));
            }
        } catch (Throwable error) {
            // 已启动的同阶段调用必须一并取消，避免批次失败后仍在后台产生副作用。
            for (CompletableFuture<ToolExecutionRecord> execution : executions) {
                execution.cancel(true);
            }
            result.completeExceptionally(error);
            return;
        }
        CompletableFuture<List<ToolExecutionRecord>> segment =
            collectInCallOrder(executions);
        active.set(segment);
        if (result.isCancelled()) {
            segment.cancel(true);
            return;
        }
        segment.whenComplete((segmentRecords, error) -> {
            active.compareAndSet(segment, null);
            if (result.isDone()) {
                return;
            }
            if (error != null) {
                result.completeExceptionally(Futures.unwrap(error));
                return;
            }
            records.addAll(segmentRecords);
            executeParallelAt(
                calls,
                contextFactory,
                invocationFactory,
                end,
                records,
                active,
                result,
                dispatchClock
            );
        });
    }

    private CompletableFuture<List<ToolExecutionRecord>> collectInCallOrder(
            List<CompletableFuture<ToolExecutionRecord>> futures) {
        final CompletableFuture<List<ToolExecutionRecord>> result =
            new CompletableFuture<List<ToolExecutionRecord>>();
        final AtomicInteger remaining = new AtomicInteger(futures.size());
        for (CompletableFuture<ToolExecutionRecord> future : futures) {
            future.whenComplete((record, error) -> {
                if (error != null) {
                    if (result.completeExceptionally(Futures.unwrap(error))) {
                        for (CompletableFuture<ToolExecutionRecord> other : futures) {
                            if (other != future) {
                                other.cancel(true);
                            }
                        }
                    }
                    return;
                }
                if (remaining.decrementAndGet() == 0 && !result.isDone()) {
                    // 不按完成先后收集，而是遍历原始 futures，保证消息历史确定性。
                    List<ToolExecutionRecord> records =
                        new ArrayList<ToolExecutionRecord>(futures.size());
                    for (CompletableFuture<ToolExecutionRecord> completed : futures) {
                        records.add(completed.join());
                    }
                    result.complete(Collections.unmodifiableList(records));
                }
            });
        }
        result.whenComplete((records, error) -> {
            if (result.isCancelled()) {
                for (CompletableFuture<ToolExecutionRecord> future : futures) {
                    future.cancel(true);
                }
            }
        });
        return result;
    }

    private boolean supportsParallelToolCalls(ToolCall call) {
        Optional<Tool> tool = registry.find(call.getName());
        return tool.isPresent() && tool.get().supportsParallelToolCalls();
    }

    private CompletableFuture<ToolExecutionRecord> executeOne(
            ToolCall call,
            ToolContext context,
            ToolInvocation invocation,
            boolean parallelAdmission,
            DispatchClock dispatchClock) {
        ExecutionClock executionClock = dispatchClock.startExecution();
        if (!interceptors.isEmpty() && invocation == null) {
            return failureOrException(
                call,
                executionClock,
                new IllegalStateException(
                    "ToolInvocation is required when interceptors are configured"
                )
            );
        }
        CancellationGroup cancellations = new CancellationGroup();
        // 拦截器可以改写 ToolCall；记录同时保留模型原始调用和实际执行调用。
        AtomicReference<ToolCall> executedCall =
            new AtomicReference<ToolCall>(call);
        final CompletableFuture<ToolResult> execution = proceedTool(
            invocation,
            call,
            context,
            0,
            cancellations,
            executedCall,
            parallelAdmission
        );
        cancellations.add(() -> execution.cancel(true));
        CompletableFuture<ToolResult> timed = withTimeout(execution, call, context);
        CompletableFuture<ToolExecutionRecord> result =
            new CompletableFuture<ToolExecutionRecord>();
        timed.whenComplete((toolResult, error) -> {
            if (error == null && toolResult != null) {
                try {
                    result.complete(new ToolExecutionRecord(
                        call,
                        executedCall.get(),
                        // 结果进入 State 和模型上下文之前必须先执行最终边界策略。
                        applyResultPolicy(toolResult),
                        executionClock.finish()
                    ));
                } catch (Throwable policyError) {
                    completeFailure(
                        result,
                        call,
                        executedCall.get(),
                        executionClock,
                        policyError
                    );
                }
                return;
            }
            Throwable actual = error == null
                ? new IllegalStateException("Tool returned null result")
                : Futures.unwrap(error);
            cancellations.cancel();
            completeFailure(
                result, call, executedCall.get(), executionClock, actual
            );
        });
        result.whenComplete((record, error) -> {
            if (result.isCancelled()) {
                cancellations.cancel();
                timed.cancel(true);
            }
        });
        return result;
    }

    private CompletableFuture<ToolResult> proceedTool(
            ToolInvocation invocation,
            ToolCall originalCall,
            ToolContext context,
            int index,
            CancellationGroup cancellations,
            AtomicReference<ToolCall> executedCall,
            boolean parallelAdmission) {
        if (!interceptors.isEmpty()) {
            Objects.requireNonNull(invocation, "invocation");
        }
        if (index >= interceptors.size()) {
            ToolCall effective = invocation == null
                ? originalCall : invocation.getCall();
            executedCall.set(effective);
            return executeTerminal(
                effective, context, cancellations, parallelAdmission
            );
        }
        // 与模型拦截器一致，工具拦截器也按注册顺序组成责任链。
        ToolInterceptor interceptor = interceptors.get(index);
        try {
            CompletableFuture<ToolResult> result = interceptor.intercept(
                invocation,
                next -> proceedTool(
                    next, originalCall, context, index + 1,
                    cancellations, executedCall, parallelAdmission
                )
            );
            if (result == null) {
                return Futures.failed(new IllegalStateException(
                    "ToolInterceptor returned null future: "
                        + interceptor.getClass().getName()
                ));
            }
            cancellations.add(() -> result.cancel(true));
            return result;
        } catch (Throwable error) {
            return Futures.failed(error);
        }
    }

    private CompletableFuture<ToolResult> executeTerminal(
            ToolCall call,
            ToolContext context,
            CancellationGroup cancellations,
            boolean parallelAdmission) {
        // 直到拦截器全部放行后才查注册表，使拦截器可以安全地重写工具名。
        Optional<Tool> resolved = registry.find(call.getName());
        if (!resolved.isPresent()) {
            return Futures.failed(new ToolFailureException(
                ToolErrorInfo.builder(
                    "UNKNOWN_TOOL", "Unknown tool: " + call.getName()
                ).retryable(true)
                    .recoveryHint(
                        "Use a tool name from the definitions supplied by the Agent."
                    )
                    .detail("tool", call.getName())
                    .build()
            ));
        }
        if (parallelAdmission
                && !resolved.get().supportsParallelToolCalls()) {
            // 拦截器可改写 Tool 名称；二次校验防止共享阶段执行有副作用的 Tool。
            return Futures.failed(new ToolFailureException(
                ToolErrorInfo.builder(
                    "TOOL_PARALLEL_POLICY_CHANGED",
                    "Tool interceptor rewrote a parallel-safe call to exclusive tool: "
                        + call.getName()
                ).retryable(false)
                    .recoveryHint(
                        "Keep the rewritten tool parallel-safe or disable parallel tool calls."
                    )
                    .detail("tool", call.getName())
                    .build()
            ));
        }
        final ToolArguments arguments;
        try {
            arguments = ToolArguments.parse(call.getArguments());
        } catch (Throwable error) {
            return Futures.failed(error);
        }
        try {
            CompletableFuture<ToolResult> execution =
                resolved.get().execute(arguments, context);
            if (execution == null) {
                return Futures.failed(new IllegalStateException(
                    "Tool returned null future"
                ));
            }
            cancellations.add(() -> execution.cancel(true));
            return execution;
        } catch (Throwable error) {
            return Futures.failed(error);
        }
    }

    private CompletableFuture<ToolExecutionRecord> failureOrException(
            ToolCall call,
            ExecutionClock executionClock,
            Throwable error) {
        CompletableFuture<ToolExecutionRecord> result =
            new CompletableFuture<ToolExecutionRecord>();
        completeFailure(result, call, executionClock, error);
        return result;
    }

    private void completeFailure(CompletableFuture<ToolExecutionRecord> target,
                                 ToolCall call,
                                 ExecutionClock executionClock,
                                 Throwable error) {
        completeFailure(target, call, call, executionClock, error);
    }

    private void completeFailure(CompletableFuture<ToolExecutionRecord> target,
                                 ToolCall call,
                                 ToolCall executedCall,
                                 ExecutionClock executionClock,
                                 Throwable error) {
        if (errorPolicy == ToolErrorPolicy.FAIL_FAST) {
            // FAIL_FAST 直接终止整个 Turn；REPORT_TO_MODEL 则生成错误 Tool 消息。
            target.completeExceptionally(error);
            return;
        }
        try {
            ToolResult failure = ToolResult.failure(
                ToolErrors.from(error, executedCall.getName())
            );
            target.complete(new ToolExecutionRecord(
                call,
                executedCall,
                applyResultPolicy(failure),
                executionClock.finish()
            ));
        } catch (Throwable policyError) {
            target.completeExceptionally(policyError);
        }
    }

    private ToolResult applyResultPolicy(ToolResult result) {
        // 默认策略会截取过大输出，并在需要时保存完整内容和恢复引用。
        ToolResult processed = resultPolicy.apply(result);
        if (processed == null) {
            throw new IllegalStateException("ToolResultPolicy returned null");
        }
        return processed;
    }

    private CompletableFuture<ToolResult> withTimeout(
            final CompletableFuture<ToolResult> source,
            ToolCall call,
            ToolContext context) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return source;
        }

        // Java 8 没有 CompletableFuture.orTimeout，使用调度任务与源 future 竞争。
        final CompletableFuture<ToolResult> target = new CompletableFuture<ToolResult>();
        long delayMillis = Math.max(1L, timeout.toMillis());
        final ScheduledFuture<?> timer = context.getScheduler().schedule(() -> {
            if (target.completeExceptionally(new TimeoutException(
                "Tool '" + call.getName() + "' timed out after " + delayMillis + " ms"
            ))) {
                source.cancel(true);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        source.whenComplete((value, error) -> {
            timer.cancel(false);
            if (error == null) {
                target.complete(value);
            } else {
                target.completeExceptionally(error);
            }
        });
        target.whenComplete((value, error) -> {
            if (target.isCancelled()) {
                source.cancel(true);
            }
        });
        return target;
    }

    /** 捕获整个模型 Tool 批次进入调度器时的墙上时间和单调时间。 */
    private static final class DispatchClock {
        private final Instant dispatchedAt;
        private final long dispatchedNanos;

        private DispatchClock(Instant dispatchedAt, long dispatchedNanos) {
            this.dispatchedAt = dispatchedAt;
            this.dispatchedNanos = dispatchedNanos;
        }

        private static DispatchClock capture() {
            long nanos = System.nanoTime();
            return new DispatchClock(Instant.now(), nanos);
        }

        private ExecutionClock startExecution() {
            long nanos = System.nanoTime();
            Instant observed = Instant.now();
            // 墙上时钟可能回拨；对外时间点保持有序，真实耗时仍来自单调时钟。
            Instant startedAt = observed.isBefore(dispatchedAt)
                ? dispatchedAt : observed;
            return new ExecutionClock(this, startedAt, nanos);
        }
    }

    /** 为一个实际开始执行的 Tool Call 完成分阶段单调计时。 */
    private static final class ExecutionClock {
        private final DispatchClock dispatchClock;
        private final Instant startedAt;
        private final long startedNanos;

        private ExecutionClock(DispatchClock dispatchClock,
                               Instant startedAt,
                               long startedNanos) {
            this.dispatchClock = dispatchClock;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
        }

        private ToolExecutionTiming finish() {
            long completedNanos = System.nanoTime();
            Instant observed = Instant.now();
            Instant completedAt = observed.isBefore(startedAt)
                ? startedAt : observed;
            return ToolExecutionTiming.measured(
                dispatchClock.dispatchedAt,
                startedAt,
                completedAt,
                elapsedNanos(dispatchClock.dispatchedNanos, startedNanos),
                elapsedNanos(startedNanos, completedNanos),
                elapsedNanos(dispatchClock.dispatchedNanos, completedNanos)
            );
        }

        private static long elapsedNanos(long start, long end) {
            return Math.max(0L, end - start);
        }
    }

    /** 汇总拦截器和实际 Tool future 的取消动作，处理取消注册之间的竞态。 */
    private static final class CancellationGroup {
        private final List<Runnable> actions = new ArrayList<Runnable>();
        private boolean cancelled;

        private void add(Runnable action) {
            boolean runNow;
            synchronized (this) {
                runNow = cancelled;
                if (!runNow) {
                    actions.add(action);
                }
            }
            if (runNow) {
                cancelQuietly(action);
            }
        }

        private void cancel() {
            List<Runnable> pending;
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                pending = new ArrayList<Runnable>(actions);
                actions.clear();
            }
            for (Runnable action : pending) {
                cancelQuietly(action);
            }
        }

        private static void cancelQuietly(Runnable action) {
            try {
                action.run();
            } catch (Throwable ignored) {
                // 插件 future 的取消是尽力而为，不能阻断其他取消动作。
            }
        }
    }
}
