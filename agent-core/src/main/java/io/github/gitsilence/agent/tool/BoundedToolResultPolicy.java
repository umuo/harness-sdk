package io.github.gitsilence.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Bounds the text inserted into model history while retaining both the start
 * and end, where summaries and final diagnostics commonly appear.
 */
public final class BoundedToolResultPolicy implements ToolResultPolicy {

    public static final int DEFAULT_MAX_BYTES = 50 * 1024;
    public static final int DEFAULT_MAX_LINES = 2000;

    private final int maxBytes;
    private final int maxLines;
    private final ToolOutputStore outputStore;

    public BoundedToolResultPolicy(int maxBytes, int maxLines) {
        this(maxBytes, maxLines, ToolOutputStore.systemTemporary());
    }

    public BoundedToolResultPolicy(int maxBytes,
                                   int maxLines,
                                   Path outputDirectory) {
        this(maxBytes, maxLines, new ToolOutputStore(outputDirectory));
    }

    public BoundedToolResultPolicy(int maxBytes,
                                   int maxLines,
                                   ToolOutputStore outputStore) {
        if (maxBytes < 256) {
            throw new IllegalArgumentException("maxBytes must be at least 256");
        }
        if (maxLines < 5) {
            throw new IllegalArgumentException("maxLines must be at least 5");
        }
        this.maxBytes = maxBytes;
        this.maxLines = maxLines;
        this.outputStore = Objects.requireNonNull(outputStore, "outputStore");
    }

    public static BoundedToolResultPolicy defaults() {
        return new BoundedToolResultPolicy(
            DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES
        );
    }

    @Override
    public ToolResult apply(ToolResult result) {
        Objects.requireNonNull(result, "result");
        String content = result.getContent();
        int originalBytes = utf8Bytes(content);
        int originalLines = lineCount(content);
        if (originalBytes <= maxBytes && originalLines <= maxLines) {
            return result;
        }

        ToolResult preserved = preserve(result, content);
        String marker = "\n\n[tool output truncated: "
            + originalBytes + "B/" + originalLines + " lines; "
            + references(preserved.getOutputReferences()) + "]\n\n";
        int contentBudget = Math.max(0, maxBytes - utf8Bytes(marker));
        TextParts lineParts = selectLines(content, maxLines - 3);
        int headBudget = (contentBudget + 1) / 2;
        int tailBudget = contentBudget / 2;
        String head = utf8Prefix(lineParts.head, headBudget);
        String tail = utf8Suffix(lineParts.tail, tailBudget);
        String bounded = head + marker + tail;

        return preserved.withContent(bounded)
            .withMetadata("toolOutputTruncated", true)
            .withMetadata("toolOutputOriginalBytes", originalBytes)
            .withMetadata("toolOutputOriginalLines", originalLines)
            .withMetadata("toolOutputRetainedBytes", utf8Bytes(bounded))
            .withMetadata("toolOutputStrategy", "head_tail");
    }

    public int getMaxBytes() { return maxBytes; }
    public int getMaxLines() { return maxLines; }
    public ToolOutputStore getOutputStore() { return outputStore; }

    private ToolResult preserve(ToolResult result, String content) {
        if (!result.getOutputReferences().isEmpty()) {
            return result.withMetadata("toolOutputPreservation", "existing_reference");
        }
        final Path path;
        try {
            path = outputStore.writeUtf8("tool-output-", content);
        } catch (IOException error) {
            throw new IllegalStateException(
                "Cannot preserve complete Tool output in "
                    + outputStore.getDirectory(),
                error
            );
        }
        return result
            .withOutputReference(ToolOutputReference.temporaryFile(
                path,
                "complete Tool result"
            ))
            .withMetadata("toolOutputPreservation", "temporary_file")
            .withMetadata("toolOutputFullPath", path.toString());
    }

    private static String references(List<ToolOutputReference> references) {
        StringBuilder text = new StringBuilder();
        if (references.size() == 1) {
            ToolOutputReference reference = references.get(0);
            text.append(reference.getKind()
                    == ToolOutputReference.Kind.SOURCE_FILE
                ? "source: " : "full output: ");
            appendReference(text, reference);
            return text.toString();
        }
        text.append("complete outputs: ");
        for (int i = 0; i < references.size(); i++) {
            ToolOutputReference reference = references.get(i);
            if (i > 0) text.append("; ");
            appendReference(text, reference);
        }
        return text.toString();
    }

    private static void appendReference(StringBuilder text,
                                        ToolOutputReference reference) {
        text.append(reference.getPath());
        if (!reference.getInstruction().isEmpty()) {
            text.append(" (").append(reference.getInstruction()).append(')');
        }
    }

    private static TextParts selectLines(String value, int retainedLines) {
        int total = lineCount(value);
        if (total <= retainedLines) {
            return new TextParts(value, value);
        }
        int headLines = (retainedLines + 1) / 2;
        int tailLines = retainedLines / 2;
        int headEnd = indexAfterLines(value, headLines);
        int tailStart = indexOfLastLines(value, tailLines);
        return new TextParts(
            trimTrailingNewline(value.substring(0, headEnd)),
            trimLeadingNewline(value.substring(tailStart))
        );
    }

    private static int indexAfterLines(String value, int lines) {
        int index = 0;
        for (int count = 0; count < lines && index < value.length(); count++) {
            int newline = value.indexOf('\n', index);
            if (newline < 0) {
                return value.length();
            }
            index = newline + 1;
        }
        return index;
    }

    private static int indexOfLastLines(String value, int lines) {
        int index = value.length();
        for (int count = 0; count < lines && index > 0; count++) {
            int newline = value.lastIndexOf('\n', index - 1);
            if (newline < 0) {
                return 0;
            }
            index = newline;
        }
        return index;
    }

    private static String utf8Prefix(String value, int budget) {
        int bytes = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int size = utf8CodePointBytes(codePoint);
            if (bytes + size > budget) {
                break;
            }
            bytes += size;
            index += Character.charCount(codePoint);
        }
        return value.substring(0, index);
    }

    private static String utf8Suffix(String value, int budget) {
        int bytes = 0;
        int index = value.length();
        while (index > 0) {
            int codePoint = value.codePointBefore(index);
            int size = utf8CodePointBytes(codePoint);
            if (bytes + size > budget) {
                break;
            }
            bytes += size;
            index -= Character.charCount(codePoint);
        }
        return value.substring(index);
    }

    private static int utf8CodePointBytes(int codePoint) {
        if (codePoint <= 0x7F) return 1;
        if (codePoint <= 0x7FF) return 2;
        if (codePoint <= 0xFFFF) return 3;
        return 4;
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int lineCount(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String trimTrailingNewline(String value) {
        return value.endsWith("\n")
            ? value.substring(0, value.length() - 1) : value;
    }

    private static String trimLeadingNewline(String value) {
        return value.startsWith("\n") ? value.substring(1) : value;
    }

    private static final class TextParts {
        private final String head;
        private final String tail;

        private TextParts(String head, String tail) {
            this.head = head;
            this.tail = tail;
        }
    }
}
