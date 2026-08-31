package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.AbstractTool;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolOutputReference;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ReadFileTool extends AbstractTool<ReadFileTool.Input> {

    private final WorkspacePathResolver paths;
    private final FileObservationTracker observations;
    private final PathLocks locks;
    private final int defaultLimit;
    private final int maxLineLength;
    private final int maxBytes;

    static Tool create(WorkspacePathResolver paths,
                       FileObservationTracker observations,
                       PathLocks locks,
                       int defaultLimit,
                       int maxLineLength,
                       int maxBytes) {
        return new ReadFileTool(
            paths, observations, locks, defaultLimit, maxLineLength, maxBytes
        );
    }

    private ReadFileTool(WorkspacePathResolver paths,
                         FileObservationTracker observations,
                         PathLocks locks,
                         int defaultLimit,
                         int maxLineLength,
                         int maxBytes) {
        super(
            "read_file",
            "Read a UTF-8 text file with 1-based line numbers. "
                + "Use offset and limit to page through large files.",
            Input.class,
            true
        );
        this.paths = paths;
        this.observations = observations;
        this.locks = locks;
        this.defaultLimit = defaultLimit;
        this.maxLineLength = maxLineLength;
        this.maxBytes = maxBytes;
    }

    @Override
    protected ToolResult execute(Input arguments, ToolContext context) {
        String input = arguments.filePath;
        int offset = arguments.offset == null ? 1 : arguments.offset;
        int limit = arguments.limit == null ? defaultLimit : arguments.limit;
        if (offset < 1) {
            throw new IllegalArgumentException("offset must be at least 1");
        }
        if (limit < 1 || limit > defaultLimit) {
            throw new IllegalArgumentException(
                "limit must be between 1 and " + defaultLimit
            );
        }
        Path path = paths.resolveReadable(input);
        synchronized (locks.forPath(path)) {
            return read(
                path, paths.display(path), offset, limit,
                maxLineLength, maxBytes, observations, context
            );
        }
    }

    static final class Input {
        @ToolParam(name = "file_path", description = "Path to the UTF-8 text file")
        public String filePath;

        @ToolParam(description = "First 1-based line to return", required = false)
        public Integer offset;

        @ToolParam(description = "Maximum number of lines to return", required = false)
        public Integer limit;
    }

    private static ToolResult read(Path path,
                                   String displayPath,
                                   int offset,
                                   int limit,
                                   int maxLineLength,
                                   int maxBytes,
                                   FileObservationTracker observations,
                                   ToolContext context) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            observations.record(context, path, observations.capture(path));
            throw BuiltinToolErrors.failure(
                "FILE_NOT_FOUND", "File not found: " + displayPath,
                "Check the path or use glob to discover matching files.", path
            );
        }
        if (!Files.isRegularFile(path)) {
            throw BuiltinToolErrors.failure(
                "NOT_A_REGULAR_FILE", "Path is not a regular file: " + displayPath,
                "Choose a UTF-8 text file. Use glob to discover files.", path
            );
        }
        try {
            if (looksBinary(path)) {
                throw BuiltinToolErrors.failure(
                    "BINARY_FILE", "Cannot read binary file as UTF-8 text: " + displayPath,
                    "Use a tool designed for this file type.", path
                );
            }
            Window window = scan(
                path, offset, limit, maxLineLength, maxBytes
            );
            if (offset > window.totalLines
                    && !(window.totalLines == 0 && offset == 1)) {
                throw BuiltinToolErrors.failure(
                    "OFFSET_OUT_OF_RANGE",
                    "offset " + offset + " is past the end of " + displayPath
                        + " (" + window.totalLines + " lines)",
                    "Use an offset between 1 and "
                        + Math.max(1, window.totalLines) + ".",
                    path
                );
            }
            observations.record(context, path, window.version);
            String content = format(displayPath, offset, window);
            int end = window.lines.isEmpty()
                ? Math.max(0, offset - 1)
                : window.lines.get(window.lines.size() - 1).number;
            return ToolResult.success(content)
                .withMetadata("path", displayPath)
                .withMetadata("offset", offset)
                .withMetadata("lineStart", window.lines.isEmpty() ? 0 : offset)
                .withMetadata("lineEnd", end)
                .withMetadata("totalLines", window.totalLines)
                .withMetadata("truncated", window.capped || end < window.totalLines)
                .withOutputReference(ToolOutputReference.sourceFile(
                    path,
                    "read_file offset/limit"
                ));
        } catch (CharacterCodingException error) {
            throw BuiltinToolErrors.failure(
                "INVALID_UTF8", "File is not valid UTF-8 text: " + displayPath,
                "Use a binary-aware tool or convert the file to UTF-8.", path
            );
        } catch (IOException error) {
            throw BuiltinToolErrors.io("read", path, error);
        }
    }

    private static Window scan(Path path,
                               int offset,
                               int limit,
                               int maxLineLength,
                               int maxBytes) throws IOException {
        List<NumberedLine> selected = new ArrayList<NumberedLine>();
        int retainedBytes = 0;
        int lineNumber = 1;
        int totalLines = 0;
        boolean sawCharacter = false;
        boolean lastWasNewline = false;
        boolean capped = false;
        boolean lineTruncated = false;
        StringBuilder line = new StringBuilder(
            Math.min(maxLineLength + 1, 4096)
        );

        MessageDigest digest = FileVersion.newDigest();
        InputStream input = new DigestInputStream(Files.newInputStream(path), digest);
        InputStreamReader decoder = new InputStreamReader(
            input,
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        );
        try (BufferedReader reader = new BufferedReader(decoder, 8192)) {
            int value;
            while ((value = reader.read()) >= 0) {
                sawCharacter = true;
                if (value == '\n') {
                    retainedBytes = retain(
                        selected, lineNumber, line, lineTruncated,
                        offset, limit, maxLineLength, maxBytes,
                        retainedBytes
                    );
                    if (retainedBytes < 0) {
                        capped = true;
                        retainedBytes = maxBytes;
                    }
                    totalLines = lineNumber;
                    lineNumber++;
                    line.setLength(0);
                    lineTruncated = false;
                    lastWasNewline = true;
                } else {
                    lastWasNewline = false;
                    if (line.length() <= maxLineLength) {
                        line.append((char) value);
                    } else {
                        lineTruncated = true;
                    }
                }
            }
            if (sawCharacter && !lastWasNewline) {
                retainedBytes = retain(
                    selected, lineNumber, line, lineTruncated,
                    offset, limit, maxLineLength, maxBytes,
                    retainedBytes
                );
                if (retainedBytes < 0) {
                    capped = true;
                }
                totalLines = lineNumber;
            }
        }
        return new Window(
            selected, totalLines, capped, FileVersion.present(digest.digest())
        );
    }

    private static int retain(List<NumberedLine> selected,
                              int number,
                              StringBuilder raw,
                              boolean wasTruncated,
                              int offset,
                              int limit,
                              int maxLineLength,
                              int maxBytes,
                              int retainedBytes) {
        if (number < offset || selected.size() >= limit
                || retainedBytes >= maxBytes) {
            return retainedBytes;
        }
        String text = raw.toString();
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.length() > maxLineLength) {
            text = text.substring(0, maxLineLength);
            wasTruncated = true;
        }
        if (wasTruncated) {
            text += "... (line truncated to " + maxLineLength + " chars)";
        }
        int bytes = (number + ": " + text + "\n")
            .getBytes(StandardCharsets.UTF_8).length;
        if (retainedBytes + bytes > maxBytes) {
            return -1;
        }
        selected.add(new NumberedLine(number, text));
        return retainedBytes + bytes;
    }

    private static boolean looksBinary(Path path) throws IOException {
        byte[] sample = new byte[4096];
        int count = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            int read;
            while (count < sample.length
                    && (read = input.read(sample, count, sample.length - count)) > 0) {
                count += read;
            }
        }
        if (count == 0) {
            return false;
        }
        int controls = 0;
        for (int i = 0; i < count; i++) {
            int value = sample[i] & 0xFF;
            if (value == 0) return true;
            if (value < 9 || (value > 13 && value < 32)) {
                controls++;
            }
        }
        if (((double) controls / (double) count) > 0.30d) {
            return true;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sample, 0, count));
        } catch (CharacterCodingException incompleteOrInvalid) {
            // The sample can end in the middle of a valid multibyte character;
            // the full streaming decoder remains authoritative.
        }
        return false;
    }

    private static String format(String path, int offset, Window window) {
        StringBuilder output = new StringBuilder()
            .append("<path>").append(path).append("</path>\n")
            .append("<type>file</type>\n<content>\n");
        for (NumberedLine line : window.lines) {
            output.append(line.number).append(": ").append(line.text).append('\n');
        }
        int end = window.lines.isEmpty()
            ? Math.max(0, offset - 1)
            : window.lines.get(window.lines.size() - 1).number;
        output.append('\n');
        if (window.capped) {
            output.append("(Output capped. Showing lines ")
                .append(offset).append('-').append(end)
                .append(". Use offset=").append(end + 1).append(" to continue.)");
        } else if (end < window.totalLines) {
            output.append("(Showing lines ").append(offset).append('-').append(end)
                .append(" of ").append(window.totalLines)
                .append(". Use offset=").append(end + 1).append(" to continue.)");
        } else {
            output.append("(End of file - total ")
                .append(window.totalLines).append(" lines)");
        }
        return output.append("\n</content>").toString();
    }

    private static final class Window {
        private final List<NumberedLine> lines;
        private final int totalLines;
        private final boolean capped;
        private final FileVersion version;

        private Window(List<NumberedLine> lines,
                       int totalLines,
                       boolean capped,
                       FileVersion version) {
            this.lines = lines;
            this.totalLines = totalLines;
            this.capped = capped;
            this.version = version;
        }
    }

    private static final class NumberedLine {
        private final int number;
        private final String text;

        private NumberedLine(int number, String text) {
            this.number = number;
            this.text = text;
        }
    }
}
