package io.github.gitsilence.agent.model.stream;

import io.github.gitsilence.agent.model.ModelResponse;

public interface ModelStreamListener {

    void onEvent(ModelStreamEvent event);

    default void onComplete(ModelResponse response) {
    }

    default void onError(Throwable error) {
    }
}
