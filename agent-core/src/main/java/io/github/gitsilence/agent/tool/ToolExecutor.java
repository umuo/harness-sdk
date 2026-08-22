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
        if (mode == ToolExecutionMode.PARALLEL) {
            return executeParallel(calls, contextFactory, invocationFactory);
        }
        return executeSequential(calls, contextFactory, invocationFactory);
    }

    private CompletableFuture<List<ToolExecutionRecord>> executeSequential(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory,
            Function<ToolCall, ToolInvocation> invocationFactory) {
        final CompletableFuture<List<ToolExecutionRecord>> result =
            new CompletableFuture<List<ToolExecutionRecord>>();
        final AtomicReference<CompletableFuture<ToolExecutionRecord>> active =
            new AtomicReference<CompletableFuture<ToolExecutionRecord>>();
        executeSequentialAt(
            calls,
            contextFactory,
            invocationFactory,
            0,
            new ArrayList<ToolExecutionRecord>(),
            active,
            result
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
            CompletableFuture<List<ToolExecutionRecord>> result) {
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
        CompletableFuture<ToolExecutionRecord> current =
            executeOne(
                call,
                contextFactory.apply(call),
                invocationFactory.apply(call)
            );
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
                index + 1, records, active, result
            );
        });
    }

    private CompletableFuture<List<ToolExecutionRecord>> executeParallel(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory,
            Function<ToolCall, ToolInvocation> invocationFactory) {
        List<CompletableFuture<ToolExecutionRecord>> futures =
            new ArrayList<CompletableFuture<ToolExecutionRecord>>();
        for (ToolCall call : calls) {
            futures.add(executeOne(
                call,
                contextFactory.apply(call),
                invocationFactory.apply(call)
            ));
        }

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

    private CompletableFuture<ToolExecutionRecord> executeOne(
            ToolCall call,
            ToolContext context,
            ToolInvocation invocation) {
        Instant startedAt = Instant.now();
        if (!interceptors.isEmpty() && invocation == null) {
            return failureOrException(call, startedAt, new IllegalStateException(
                "ToolInvocation is required when interceptors are configured"
            ));
        }
        CancellationGroup cancellations = new CancellationGroup();
        AtomicReference<ToolCall> executedCall =
            new AtomicReference<ToolCall>(call);
        final CompletableFuture<ToolResult> execution = proceedTool(
            invocation, call, context, 0, cancellations, executedCall
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
                        applyResultPolicy(toolResult),
                        startedAt,
                        Instant.now()
                    ));
                } catch (Throwable policyError) {
                    completeFailure(
                        result, call, executedCall.get(), startedAt, policyError
                    );
                }
                return;
            }
            Throwable actual = error == null
                ? new IllegalStateException("Tool returned null result")
                : Futures.unwrap(error);
            cancellations.cancel();
            completeFailure(result, call, executedCall.get(), startedAt, actual);
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
            AtomicReference<ToolCall> executedCall) {
        if (!interceptors.isEmpty()) {
            Objects.requireNonNull(invocation, "invocation");
        }
        if (index >= interceptors.size()) {
            ToolCall effective = invocation == null
                ? originalCall : invocation.getCall();
            executedCall.set(effective);
            return executeTerminal(effective, context, cancellations);
        }
        ToolInterceptor interceptor = interceptors.get(index);
        try {
            CompletableFuture<ToolResult> result = interceptor.intercept(
                invocation,
                next -> proceedTool(
                    next, originalCall, context, index + 1,
                    cancellations, executedCall
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
            CancellationGroup cancellations) {
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
            Instant startedAt,
            Throwable error) {
        CompletableFuture<ToolExecutionRecord> result =
            new CompletableFuture<ToolExecutionRecord>();
        completeFailure(result, call, startedAt, error);
        return result;
    }

    private void completeFailure(CompletableFuture<ToolExecutionRecord> target,
                                 ToolCall call,
                                 Instant startedAt,
                                 Throwable error) {
        completeFailure(target, call, call, startedAt, error);
    }

    private void completeFailure(CompletableFuture<ToolExecutionRecord> target,
                                 ToolCall call,
                                 ToolCall executedCall,
                                 Instant startedAt,
                                 Throwable error) {
        if (errorPolicy == ToolErrorPolicy.FAIL_FAST) {
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
                startedAt,
                Instant.now()
            ));
        } catch (Throwable policyError) {
            target.completeExceptionally(policyError);
        }
    }

    private ToolResult applyResultPolicy(ToolResult result) {
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
                // Cancellation remains best effort for plugin futures.
            }
        }
    }
}
