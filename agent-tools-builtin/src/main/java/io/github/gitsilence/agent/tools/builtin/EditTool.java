package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.AbstractTool;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class EditTool extends AbstractTool<EditTool.Input> {

    private final WorkspacePathResolver paths;
    private final FileObservationTracker observations;
    private final PathLocks locks;
    private final int maxEditableBytes;

    static Tool create(WorkspacePathResolver paths,
                       FileObservationTracker observations,
                       PathLocks locks,
                       int maxEditableBytes) {
        return new EditTool(paths, observations, locks, maxEditableBytes);
    }

    private EditTool(WorkspacePathResolver paths,
                     FileObservationTracker observations,
                     PathLocks locks,
                     int maxEditableBytes) {
        super(
            "edit",
            "Replace exact text in an existing UTF-8 file. By default the "
                + "old_string must occur exactly once; set replace_all for all matches.",
            Input.class
        );
        this.paths = paths;
        this.observations = observations;
        this.locks = locks;
        this.maxEditableBytes = maxEditableBytes;
    }

    @Override
    protected ToolResult execute(Input arguments, ToolContext context) {
        String input = arguments.filePath;
        String oldString = arguments.oldString;
        String newString = arguments.newString;
        boolean replaceAll = Boolean.TRUE.equals(arguments.replaceAll);
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("old_string must not be empty");
        }
        if (oldString.equals(newString)) {
            throw new IllegalArgumentException(
                "old_string and new_string must be different"
            );
        }
        Path path = paths.resolve(input);
        synchronized (locks.forPath(path)) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw BuiltinToolErrors.failure(
                    "FILE_NOT_FOUND", "File not found: " + paths.display(path),
                    "Use glob to find the file, then read it before editing.", path
                );
            }
            if (!Files.isRegularFile(path)) {
                throw BuiltinToolErrors.failure(
                    "NOT_A_REGULAR_FILE", "Path is not a file: " + paths.display(path),
                    "Choose a regular UTF-8 text file.", path
                );
            }
            byte[] bytes;
            try {
                long size = Files.size(path);
                if (size > maxEditableBytes) {
                    throw BuiltinToolErrors.failure(
                        "EDIT_FILE_TOO_LARGE",
                        "File is " + size + " bytes; edit limit is "
                            + maxEditableBytes,
                        "Use a more specialized streaming edit or rewrite a smaller file.",
                        path
                    );
                }
                bytes = Files.readAllBytes(path);
            } catch (IOException error) {
                throw BuiltinToolErrors.io("read for edit", path, error);
            }
            String content = decodeUtf8(bytes, path, paths.display(path));
            FileVersion loaded = FileVersion.fromBytes(bytes);
            observations.requireCurrent(context, path, loaded);
            int occurrences = countOccurrences(content, oldString);
            if (occurrences == 0) {
                throw BuiltinToolErrors.failure(
                    "EDIT_TEXT_NOT_FOUND",
                    "old_string was not found in " + paths.display(path),
                    "Re-read the file and copy the exact text to replace.", path
                );
            }
            if (!replaceAll && occurrences > 1) {
                throw BuiltinToolErrors.failure(
                    "EDIT_TEXT_NOT_UNIQUE",
                    "old_string occurs " + occurrences + " times in "
                        + paths.display(path),
                    "Provide a larger unique old_string or set replace_all to true.",
                    path
                );
            }
            String changed = replaceAll
                ? content.replace(oldString, newString)
                : replaceFirstLiteral(content, oldString, newString);
            int changedBytes = changed.getBytes(StandardCharsets.UTF_8).length;
            if (changedBytes > maxEditableBytes) {
                throw BuiltinToolErrors.failure(
                    "EDIT_RESULT_TOO_LARGE",
                    "Edited file would be " + changedBytes
                        + " bytes; edit limit is " + maxEditableBytes,
                    "Use a smaller replacement or write a smaller file.", path
                );
            }
            try {
                observations.requireCurrent(
                    context, path, observations.capture(path)
                );
                AtomicFileWriter.writeUtf8(path, changed);
            } catch (IOException error) {
                throw BuiltinToolErrors.io("edit", path, error);
            }
            observations.record(context, path, observations.capture(path));
            int replacements = replaceAll ? occurrences : 1;
            return ToolResult.success(
                "Updated " + paths.display(path) + " successfully ("
                    + replacements + " replacement"
                    + (replacements == 1 ? "" : "s") + ")."
            ).withMetadata("path", paths.display(path))
                .withMetadata("replacements", replacements)
                .withMetadata("bytesWritten", changedBytes);
        }
    }

    static final class Input {
        @ToolParam(name = "file_path", description = "Existing UTF-8 file to edit")
        public String filePath;

        @ToolParam(name = "old_string", description = "Exact text to replace")
        public String oldString;

        @ToolParam(name = "new_string", description = "Replacement text")
        public String newString;

        @ToolParam(
            name = "replace_all",
            description = "Replace every occurrence instead of requiring one match",
            required = false
        )
        public Boolean replaceAll;
    }

    private static String decodeUtf8(byte[] bytes, Path path, String displayPath) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw BuiltinToolErrors.failure(
                "INVALID_UTF8", "File is not valid UTF-8 text: " + displayPath,
                "Use a binary-aware tool or convert the file to UTF-8.", path
            );
        }
    }

    private static int countOccurrences(String content, String search) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(search, index)) >= 0) {
            count++;
            index += search.length();
        }
        return count;
    }

    private static String replaceFirstLiteral(String content,
                                              String oldString,
                                              String newString) {
        int index = content.indexOf(oldString);
        return content.substring(0, index)
            + newString
            + content.substring(index + oldString.length());
    }
}
