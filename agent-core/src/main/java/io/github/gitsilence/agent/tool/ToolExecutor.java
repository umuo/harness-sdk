package io.github.gitsilence.agent.tool;

import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.runtime.Futures;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolExecutionMode mode;
    private final ToolErrorPolicy errorPolicy;
    private final Duration timeout;

    public ToolExecutor(ToolRegistry registry,
                        ToolExecutionMode mode,
                        ToolErrorPolicy errorPolicy,
                        Duration timeout) {
        this.registry = registry;
        this.mode = mode;
        this.errorPolicy = errorPolicy;
        this.timeout = timeout;
    }

    public CompletableFuture<List<ToolExecutionRecord>> executeAll(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory) {
        if (calls.isEmpty()) {
            return CompletableFuture.completedFuture(
                Collections.<ToolExecutionRecord>emptyList()
            );
        }
        if (mode == ToolExecutionMode.PARALLEL) {
            return executeParallel(calls, contextFactory);
        }
        return executeSequential(calls, contextFactory);
    }

    private CompletableFuture<List<ToolExecutionRecord>> executeSequential(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory) {
        CompletableFuture<List<ToolExecutionRecord>> chain =
            CompletableFuture.completedFuture(new ArrayList<ToolExecutionRecord>());

        for (final ToolCall call : calls) {
            chain = chain.thenCompose(records ->
                executeOne(call, contextFactory.apply(call)).thenApply(record -> {
                    records.add(record);
                    return records;
                })
            );
        }
        return chain.thenApply(records -> Collections.unmodifiableList(
            new ArrayList<ToolExecutionRecord>(records)
        ));
    }

    private CompletableFuture<List<ToolExecutionRecord>> executeParallel(
            List<ToolCall> calls,
            Function<ToolCall, ToolContext> contextFactory) {
        List<CompletableFuture<ToolExecutionRecord>> futures =
            new ArrayList<CompletableFuture<ToolExecutionRecord>>();
        for (ToolCall call : calls) {
            futures.add(executeOne(call, contextFactory.apply(call)));
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
        return result;
    }

    private CompletableFuture<ToolExecutionRecord> executeOne(ToolCall call,
                                                               ToolContext context) {
        Instant startedAt = Instant.now();
        Optional<Tool> resolved = registry.find(call.getName());
        if (!resolved.isPresent()) {
            return failureOrException(
                call,
                startedAt,
                new IllegalArgumentException("Unknown tool: " + call.getName())
            );
        }

        final ToolArguments arguments;
        try {
            arguments = ToolArguments.parse(call.getArguments());
        } catch (Throwable error) {
            return failureOrException(call, startedAt, error);
        }

        final CompletableFuture<ToolResult> execution;
        try {
            execution = resolved.get().execute(arguments, context);
            if (execution == null) {
                return failureOrException(
                    call,
                    startedAt,
                    new IllegalStateException("Tool returned null future")
                );
            }
        } catch (Throwable error) {
            return failureOrException(call, startedAt, error);
        }

        CompletableFuture<ToolResult> timed = withTimeout(execution, call, context);
        CompletableFuture<ToolExecutionRecord> result =
            new CompletableFuture<ToolExecutionRecord>();
        timed.whenComplete((toolResult, error) -> {
            if (error == null && toolResult != null) {
                result.complete(new ToolExecutionRecord(
                    call, toolResult, startedAt, Instant.now()
                ));
                return;
            }
            Throwable actual = error == null
                ? new IllegalStateException("Tool returned null result")
                : Futures.unwrap(error);
            completeFailure(result, call, startedAt, actual);
        });
        result.whenComplete((record, error) -> {
            if (result.isCancelled()) {
                timed.cancel(true);
            }
        });
        return result;
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
        if (errorPolicy == ToolErrorPolicy.FAIL_FAST) {
            target.completeExceptionally(error);
            return;
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        target.complete(new ToolExecutionRecord(
            call,
            ToolResult.failure("Tool error: " + message),
            startedAt,
            Instant.now()
        ));
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
}
