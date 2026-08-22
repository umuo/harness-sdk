package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.Tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class WriteFileTool {

    private WriteFileTool() {
    }

    static Tool create(WorkspacePathResolver paths,
                       FileObservationTracker observations,
                       PathLocks locks,
                       int maxWriteBytes) {
        ToolDefinition definition = ToolDefinition.builder()
            .name("write_file")
            .description(
                "Create or completely replace a UTF-8 text file. "
                    + "Read an existing file before overwriting it."
            )
            .inputSchema("{\"type\":\"object\",\"properties\":{"
                + "\"file_path\":{\"type\":\"string\"},"
                + "\"content\":{\"type\":\"string\"}},"
                + "\"required\":[\"file_path\",\"content\"],"
                + "\"additionalProperties\":false}")
            .build();
        return Tools.sync(definition, (arguments, context) -> {
            String input = arguments.requireString("file_path");
            String content = arguments.requireString("content");
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxWriteBytes) {
                throw BuiltinToolErrors.failure(
                    "WRITE_TOO_LARGE",
                    "Content is " + bytes + " bytes; maximum is " + maxWriteBytes,
                    "Write a smaller file or split the content into focused files.",
                    null
                );
            }
            Path path = paths.resolve(input);
            synchronized (locks.forPath(path)) {
                boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
                if (existed && !Files.isRegularFile(path)) {
                    throw BuiltinToolErrors.failure(
                        "NOT_A_REGULAR_FILE",
                        "Cannot overwrite a non-file path: " + paths.display(path),
                        "Choose a regular file path.", path
                    );
                }
                FileVersion before = observations.capture(path);
                observations.requireCurrent(context, path, before);
                try {
                    AtomicFileWriter.writeUtf8(path, content);
                } catch (IOException error) {
                    throw BuiltinToolErrors.io("write", path, error);
                }
                observations.record(context, path, observations.capture(path));
                String operation = existed ? "updated" : "created";
                return ToolResult.success(
                    "<path>" + paths.display(path) + "</path>\n"
                        + "<type>file</type>\n<content>\n"
                        + Character.toUpperCase(operation.charAt(0))
                        + operation.substring(1) + " file\n</content>"
                ).withMetadata("path", paths.display(path))
                    .withMetadata("operation", operation)
                    .withMetadata("bytesWritten", bytes);
            }
        });
    }
}
