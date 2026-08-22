package io.github.gitsilence.agent.runtime;

public enum AgentEventType {
    TURN_STARTED,
    STEP_STARTED,
    MODEL_STARTED,
    MODEL_STREAM_EVENT,
    MODEL_COMPLETED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    STEP_COMPLETED,
    TURN_COMPLETED,
    TURN_STOPPED,
    TURN_FAILED,
    TURN_CANCELLED
}
