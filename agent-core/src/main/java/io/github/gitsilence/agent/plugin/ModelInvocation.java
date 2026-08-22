package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.state.AgentStateSnapshot;

import java.util.Objects;

public final class ModelInvocation {

    private final String turnId;
    private final String agentName;
    private final int step;
    private final ModelRequest request;
    private final AgentStateSnapshot state;
    private final boolean streaming;

    public ModelInvocation(String turnId,
                           String agentName,
                           int step,
                           ModelRequest request,
                           AgentStateSnapshot state,
                           boolean streaming) {
        this.turnId = requireText(turnId, "turnId");
        this.agentName = requireText(agentName, "agentName");
        this.step = step;
        this.request = Objects.requireNonNull(request, "request");
        this.state = Objects.requireNonNull(state, "state");
        this.streaming = streaming;
    }

    public ModelInvocation withRequest(ModelRequest request) {
        return new ModelInvocation(
            turnId, agentName, step, request, state, streaming
        );
    }

    public String getTurnId() { return turnId; }
    public String getRunId() { return turnId; }
    public String getAgentName() { return agentName; }
    public int getStep() { return step; }
    public ModelRequest getRequest() { return request; }
    public AgentStateSnapshot getState() { return state; }
    public boolean isStreaming() { return streaming; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
