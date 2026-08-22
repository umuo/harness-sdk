package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.state.AgentStateSnapshot;

import java.util.Objects;

public final class ToolInvocation {

    private final String turnId;
    private final String agentName;
    private final int step;
    private final ToolCall call;
    private final AgentStateSnapshot state;

    public ToolInvocation(String turnId,
                          String agentName,
                          int step,
                          ToolCall call,
                          AgentStateSnapshot state) {
        this.turnId = requireText(turnId, "turnId");
        this.agentName = requireText(agentName, "agentName");
        this.step = step;
        this.call = Objects.requireNonNull(call, "call");
        this.state = Objects.requireNonNull(state, "state");
    }

    public ToolInvocation withCall(ToolCall call) {
        Objects.requireNonNull(call, "call");
        if (!this.call.getId().equals(call.getId())) {
            throw new IllegalArgumentException("A ToolInterceptor cannot replace call id");
        }
        return new ToolInvocation(turnId, agentName, step, call, state);
    }

    public String getTurnId() { return turnId; }
    public String getRunId() { return turnId; }
    public String getAgentName() { return agentName; }
    public int getStep() { return step; }
    public ToolCall getCall() { return call; }
    public AgentStateSnapshot getState() { return state; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
