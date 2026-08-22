package io.github.gitsilence.agent.runtime;

@FunctionalInterface
public interface AgentEventListener {

    void onEvent(AgentEvent event);

    static AgentEventListener noop() {
        return event -> { };
    }
}
