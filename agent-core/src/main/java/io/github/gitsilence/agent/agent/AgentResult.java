package io.github.gitsilence.agent.agent;

import io.github.gitsilence.agent.runtime.ExecutionStatus;
import io.github.gitsilence.agent.state.AgentStateSnapshot;

import java.util.Objects;

public final class AgentResult {

    private final AgentStateSnapshot state;

    public AgentResult(AgentStateSnapshot state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public boolean isCompleted() {
        return state.getStatus() == ExecutionStatus.COMPLETED;
    }

    public ExecutionStatus getStatus() {
        return state.getStatus();
    }

    public String getOutput() {
        return state.getFinalOutput();
    }

    public String getStopReason() {
        return state.getStopReason();
    }

    public AgentStateSnapshot getState() {
        return state;
    }
}
