package io.github.gitsilence.agent.agent;

import io.github.gitsilence.agent.runtime.Futures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small composition helpers. This is intentionally not a workflow runtime.
 */
public final class AgentExecutions {

    private AgentExecutions() {
    }

    /**
     * Starts all invocations immediately and returns results in input order.
     * The returned future is fail-fast and cancellation propagates to every
     * unfinished invocation.
     */
    public static CompletableFuture<List<AgentResult>> runParallel(
            List<AgentInvocation> invocations) {
        Objects.requireNonNull(invocations, "invocations");
        if (invocations.isEmpty()) {
            return CompletableFuture.completedFuture(
                Collections.<AgentResult>emptyList()
            );
        }

        final List<CompletableFuture<AgentResult>> futures =
            new ArrayList<CompletableFuture<AgentResult>>(invocations.size());
        try {
            for (AgentInvocation invocation : invocations) {
                Objects.requireNonNull(invocation, "invocation");
                futures.add(invocation.getAgent().runAsync(invocation.getRequest()));
            }
        } catch (Throwable error) {
            cancelAll(futures);
            return Futures.failed(error);
        }

        final CompletableFuture<List<AgentResult>> result =
            new CompletableFuture<List<AgentResult>>();
        final AtomicInteger remaining = new AtomicInteger(futures.size());
        for (CompletableFuture<AgentResult> future : futures) {
            future.whenComplete((value, error) -> {
                if (error != null) {
                    if (result.completeExceptionally(Futures.unwrap(error))) {
                        cancelAllExcept(futures, future);
                    }
                    return;
                }
                if (remaining.decrementAndGet() == 0 && !result.isDone()) {
                    List<AgentResult> ordered =
                        new ArrayList<AgentResult>(futures.size());
                    for (CompletableFuture<AgentResult> completed : futures) {
                        ordered.add(completed.join());
                    }
                    result.complete(Collections.unmodifiableList(ordered));
                }
            });
        }
        result.whenComplete((values, error) -> {
            if (result.isCancelled()) {
                cancelAll(futures);
            }
        });
        return result;
    }

    private static void cancelAll(List<CompletableFuture<AgentResult>> futures) {
        for (CompletableFuture<AgentResult> future : futures) {
            future.cancel(true);
        }
    }

    private static void cancelAllExcept(
            List<CompletableFuture<AgentResult>> futures,
            CompletableFuture<AgentResult> completed) {
        for (CompletableFuture<AgentResult> future : futures) {
            if (future != completed) {
                future.cancel(true);
            }
        }
    }
}
