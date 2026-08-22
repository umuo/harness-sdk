package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

final class FileObservationTracker {

    private static final String KEY_PREFIX =
        "io.github.gitsilence.agent.tools.builtin.observed:";

    private final boolean required;

    FileObservationTracker(boolean required) {
        this.required = required;
    }

    void record(ToolContext context, Path path, FileVersion version) {
        context.putVariable(key(path), version);
    }

    void requireCurrent(ToolContext context, Path path, FileVersion current) {
        if (!required || !current.isPresent()) {
            return;
        }
        Optional<Object> observed = context.variable(key(path));
        if (!observed.isPresent() || !(observed.get() instanceof FileVersion)) {
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "FILE_NOT_OBSERVED",
                    "The file has not been read in this Turn: " + path
                ).retryable(true)
                    .recoveryHint("Read the file, then retry the mutation.")
                    .detail("path", path)
                    .build()
            );
        }
        if (!current.equals(observed.get())) {
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "FILE_CHANGED_SINCE_READ",
                    "The file changed after it was last read: " + path
                ).retryable(true)
                    .recoveryHint("Re-read the file, adjust the change, then retry.")
                    .detail("path", path)
                    .build()
            );
        }
    }

    FileVersion capture(Path path) {
        try {
            return FileVersion.capture(path);
        } catch (IOException error) {
            throw BuiltinToolErrors.io("inspect", path, error);
        }
    }

    private static String key(Path path) {
        return KEY_PREFIX + path.toAbsolutePath().normalize();
    }
}
