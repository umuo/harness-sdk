package io.github.gitsilence.agent.tool;

import java.util.Objects;

/** Allows a Tool to fail with a stable code and a model-actionable remedy. */
public final class ToolFailureException extends RuntimeException {

    private final ToolErrorInfo errorInfo;

    public ToolFailureException(ToolErrorInfo errorInfo) {
        this(errorInfo, null);
    }

    public ToolFailureException(ToolErrorInfo errorInfo, Throwable cause) {
        super(Objects.requireNonNull(errorInfo, "errorInfo").getMessage(), cause);
        this.errorInfo = errorInfo;
    }

    public ToolErrorInfo getErrorInfo() {
        return errorInfo;
    }
}
