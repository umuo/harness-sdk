package io.github.gitsilence.agent.model.stream;

import io.github.gitsilence.agent.model.ModelResponse;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModelStream {

    private final CompletableFuture<ModelResponse> completion;
    private final Runnable cancellation;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public ModelStream(CompletableFuture<ModelResponse> completion,
                       Runnable cancellation) {
        this.completion = Objects.requireNonNull(completion, "completion");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public CompletableFuture<ModelResponse> completion() {
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

    public boolean isCancelled() {
        return cancelled.get() || completion.isCancelled();
    }
}
