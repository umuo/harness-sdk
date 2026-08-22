package io.github.gitsilence.agent.http;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HttpStreamHandle {

    private final CompletableFuture<Void> completion;
    private final Runnable cancellation;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public HttpStreamHandle(CompletableFuture<Void> completion, Runnable cancellation) {
        this.completion = Objects.requireNonNull(completion, "completion");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        completion.cancel(true);
        cancellation.run();
        return true;
    }
}
