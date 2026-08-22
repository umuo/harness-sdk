package io.github.gitsilence.agent.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class InvocationPath {

    private final List<String> agentIds;
    private final List<String> agentNames;

    private InvocationPath(List<String> agentIds, List<String> agentNames) {
        this.agentIds = Collections.unmodifiableList(new ArrayList<String>(agentIds));
        this.agentNames = Collections.unmodifiableList(new ArrayList<String>(agentNames));
    }

    public static InvocationPath root(String agentId, String agentName) {
        return new InvocationPath(
            Collections.singletonList(Objects.requireNonNull(agentId, "agentId")),
            Collections.singletonList(Objects.requireNonNull(agentName, "agentName"))
        );
    }

    public InvocationPath append(String agentId, String agentName) {
        List<String> ids = new ArrayList<String>(agentIds);
        List<String> names = new ArrayList<String>(agentNames);
        ids.add(Objects.requireNonNull(agentId, "agentId"));
        names.add(Objects.requireNonNull(agentName, "agentName"));
        return new InvocationPath(ids, names);
    }

    public boolean containsAgent(String agentId) {
        return agentIds.contains(agentId);
    }

    public int depth() {
        return agentIds.size();
    }

    public List<String> getAgentNames() {
        return agentNames;
    }

    @Override
    public String toString() {
        return agentNames.toString();
    }
}
