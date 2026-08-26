package io.github.gitsilence.agent.model.stream;

import io.github.gitsilence.agent.model.ModelResponse;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 模型增量流的句柄：一个最终响应 future 加一个底层传输取消动作。 */
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
        // CAS 保证底层断流动作最多执行一次。
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
