package io.github.gitsilence.agent.observability;

/** Receives one immutable trace segment after an Agent Turn terminates. */
@FunctionalInterface
public interface AgentTraceExporter {

    void export(AgentTrace trace);

    static AgentTraceExporter noop() {
        return trace -> { };
    }
}
