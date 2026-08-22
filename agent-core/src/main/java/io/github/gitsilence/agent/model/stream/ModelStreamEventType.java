package io.github.gitsilence.agent.model.stream;

public enum ModelStreamEventType {
    RESPONSE_STARTED,
    TEXT_DELTA,
    TOOL_CALL_STARTED,
    TOOL_ARGUMENTS_DELTA,
    USAGE
}
