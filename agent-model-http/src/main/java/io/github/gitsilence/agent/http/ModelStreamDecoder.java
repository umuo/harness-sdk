package io.github.gitsilence.agent.http;

import io.github.gitsilence.agent.model.ModelResponse;

public interface ModelStreamDecoder {

    void onEvent(SseEvent event) throws Exception;

    ModelResponse finish() throws Exception;
}
