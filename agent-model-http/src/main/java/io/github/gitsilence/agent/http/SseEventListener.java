package io.github.gitsilence.agent.http;

public interface SseEventListener {

    void onEvent(SseEvent event) throws Exception;
}
