package io.github.gitsilence.agent.observability;

/** The fixed execution levels traced by the Agent harness. */
public enum AgentSpanKind {
    TURN,
    STEP,
    MODEL,
    TOOL
}
