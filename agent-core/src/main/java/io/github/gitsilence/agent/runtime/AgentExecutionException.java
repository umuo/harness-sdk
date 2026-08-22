package io.github.gitsilence.agent.runtime;

import io.github.gitsilence.agent.state.AgentStateSnapshot;

public class AgentExecutionException extends RuntimeException {

    private final AgentStateSnapshot state;

    public AgentExecutionException(String message,
                                   Throwable cause,
                                   AgentStateSnapshot state) {
        super(message, cause);
        this.state = state;
    }

    public AgentStateSnapshot getState() {
        return state;
    }
}
