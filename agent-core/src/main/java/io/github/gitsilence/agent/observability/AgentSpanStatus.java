package io.github.gitsilence.agent.observability;

/** Normalized outcome of one observable execution interval. */
public enum AgentSpanStatus {
    OK,
    ERROR,
    STOPPED,
    CANCELLED
}
