package io.github.gitsilence.agent.agent;

import java.util.Objects;

/**
 * One independent Agent call used by lightweight multi-Agent composition.
 */
public final class AgentInvocation {

    private final Agent agent;
    private final AgentRequest request;

    private AgentInvocation(Agent agent, AgentRequest request) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.request = Objects.requireNonNull(request, "request");
    }

    public static AgentInvocation of(Agent agent, String input) {
        return of(agent, AgentRequest.of(input));
    }

    public static AgentInvocation of(Agent agent, AgentRequest request) {
        return new AgentInvocation(agent, request);
    }

    public Agent getAgent() {
        return agent;
    }

    public AgentRequest getRequest() {
        return request;
    }
}
