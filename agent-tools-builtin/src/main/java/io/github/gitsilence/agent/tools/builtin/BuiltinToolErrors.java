package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;

import java.io.IOException;
import java.nio.file.Path;

final class BuiltinToolErrors {

    private BuiltinToolErrors() {
    }

    static ToolFailureException io(String action, Path path, IOException error) {
        return new ToolFailureException(
            ToolErrorInfo.builder(
                "FILE_IO_ERROR",
                "Cannot " + action + " '" + path + "': " + error.getMessage()
            ).retryable(true)
                .recoveryHint("Check the path and permissions, then retry if appropriate.")
                .detail("path", path)
                .detail("operation", action)
                .build(),
            error
        );
    }

    static ToolFailureException failure(String code,
                                        String message,
                                        String recovery,
                                        Path path) {
        ToolErrorInfo.Builder error = ToolErrorInfo.builder(code, message)
            .retryable(true)
            .recoveryHint(recovery);
        if (path != null) {
            error.detail("path", path);
        }
        return new ToolFailureException(error.build());
    }
}
