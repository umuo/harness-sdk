package io.github.gitsilence.agent.model;

import java.util.concurrent.CompletableFuture;

public interface ChatModel {

    CompletableFuture<ModelResponse> generate(ModelRequest request);
}
