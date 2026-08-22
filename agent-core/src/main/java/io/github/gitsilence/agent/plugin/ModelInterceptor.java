package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.model.ModelResponse;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ModelInterceptor {

    CompletableFuture<ModelResponse> intercept(
        ModelInvocation invocation,
        ModelChain chain
    );
}
