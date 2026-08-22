package io.github.gitsilence.agent.tool;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

final class ToolErrors {

    private ToolErrors() {
    }

    static ToolErrorInfo from(Throwable error, String toolName) {
        ToolFailureException declared = find(error, ToolFailureException.class);
        if (declared != null) {
            return declared.getErrorInfo();
        }
        if (find(error, TimeoutException.class) != null) {
            return ToolErrorInfo.builder(
                "TOOL_TIMEOUT", message(error, "Tool execution timed out")
            ).retryable(true)
                .recoveryHint(
                    "Retry with a smaller or more targeted request."
                )
                .detail("tool", toolName)
                .build();
        }
        if (find(error, IllegalArgumentException.class) != null) {
            return ToolErrorInfo.builder(
                "INVALID_TOOL_ARGUMENTS", message(error, "Invalid tool arguments")
            ).retryable(true)
                .recoveryHint(
                    "Correct the arguments to match the tool schema and retry."
                )
                .detail("tool", toolName)
                .build();
        }
        if (find(error, IOException.class) != null) {
            return ToolErrorInfo.builder(
                "TOOL_IO_ERROR", message(error, "Tool I/O failed")
            ).retryable(true)
                .recoveryHint(
                    "Check the path and permissions, then retry if appropriate."
                )
                .detail("tool", toolName)
                .build();
        }
        return ToolErrorInfo.builder(
            "TOOL_EXECUTION_FAILED", message(error, "Tool execution failed")
        ).retryable(false)
            .recoveryHint(
                "Inspect the error and choose a different action or arguments."
            )
            .detail("tool", toolName)
            .build();
    }

    private static String message(Throwable error, String fallback) {
        Throwable current = error;
        String candidate = null;
        while (current != null) {
            if (current.getMessage() != null
                    && !current.getMessage().trim().isEmpty()) {
                candidate = current.getMessage();
            }
            current = current.getCause();
        }
        return candidate == null ? fallback : candidate;
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
