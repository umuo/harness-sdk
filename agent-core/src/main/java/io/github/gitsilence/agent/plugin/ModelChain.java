package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.model.ModelResponse;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ModelChain {

    CompletableFuture<ModelResponse> proceed(ModelInvocation invocation);
}
