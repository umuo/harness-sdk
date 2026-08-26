package io.github.gitsilence.agent.tools.builtin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 在保留原始行结束符的前提下，把已解析的更新块应用到 UTF-8 文本。 */
final class PatchTextFile {

    private final List<SourceLine> lines;
    private final LineEnding preferredEnding;

    private PatchTextFile(List<SourceLine> lines, LineEnding preferredEnding) {
        this.lines = lines;
        this.preferredEnding = preferredEnding;
    }

    static String apply(String original,
                        List<ApplyPatchParser.Chunk> chunks,
                        String displayPath) {
        PatchTextFile source = parse(original);
        List<String> originalLines = source.lineTexts();
        List<Replacement> replacements = computeReplacements(
            originalLines, chunks, displayPath
        );
        source.applyReplacements(replacements);
        return source.contents();
    }

    private static PatchTextFile parse(String contents) {
        List<SourceLine> lines = new ArrayList<SourceLine>();
        LineEnding preferred = null;
        int start = 0;
        int cursor = 0;
        while (cursor < contents.length()) {
            char current = contents.charAt(cursor);
            LineEnding ending = null;
            int endingLength = 0;
            if (current == '\r' && cursor + 1 < contents.length()
                    && contents.charAt(cursor + 1) == '\n') {
                ending = LineEnding.CRLF;
                endingLength = 2;
            } else if (current == '\r') {
                ending = LineEnding.CR;
                endingLength = 1;
            } else if (current == '\n') {
                ending = LineEnding.LF;
                endingLength = 1;
            }
            if (ending == null) {
                cursor++;
                continue;
            }
            if (preferred == null) preferred = ending;
            lines.add(new SourceLine(contents.substring(start, cursor), ending));
            cursor += endingLength;
            start = cursor;
        }
        if (start < contents.length()) {
            lines.add(new SourceLine(contents.substring(start), null));
        }
        return new PatchTextFile(
            lines, preferred == null ? LineEnding.LF : preferred
        );
    }

    private List<String> lineTexts() {
        List<String> result = new ArrayList<String>(lines.size());
        for (SourceLine line : lines) result.add(line.text);
        return result;
    }

    private static List<Replacement> computeReplacements(
            List<String> original,
            List<ApplyPatchParser.Chunk> chunks,
            String displayPath) {
        List<Replacement> replacements = new ArrayList<Replacement>();
        int lineIndex = 0;
        for (ApplyPatchParser.Chunk chunk : chunks) {
            if (chunk.getContext() != null) {
                int contextIndex = seek(
                    original,
                    Collections.singletonList(chunk.getContext()),
                    lineIndex,
                    false
                );
                if (contextIndex < 0) {
                    throw contextFailure(
                        "Failed to find context '" + chunk.getContext()
                            + "' in " + displayPath
                    );
                }
                lineIndex = contextIndex + 1;
            }

            List<String> pattern = chunk.getOldLines();
            List<String> replacement = chunk.getNewLines();
            if (pattern.isEmpty()) {
                // 与 Codex 一致：没有旧行的纯新增块追加到文件尾部。
                replacements.add(new Replacement(
                    original.size(), 0, replacement
                ));
                continue;
            }

            int found = seek(
                original, pattern, lineIndex, chunk.isEndOfFile()
            );
            if (found < 0 && pattern.get(pattern.size() - 1).isEmpty()) {
                pattern = pattern.subList(0, pattern.size() - 1);
                if (!replacement.isEmpty()
                        && replacement.get(replacement.size() - 1).isEmpty()) {
                    replacement = replacement.subList(0, replacement.size() - 1);
                }
                found = seek(
                    original, pattern, lineIndex, chunk.isEndOfFile()
                );
            }
            if (found < 0) {
                throw contextFailure(
                    "Failed to find expected lines in " + displayPath
                        + ":\n" + join(pattern)
                );
            }
            addContentReplacements(
                replacements, found, pattern, replacement,
                chunk.getContextIndices()
            );
            lineIndex = found + pattern.size();
        }
        Collections.sort(replacements, new Comparator<Replacement>() {
            @Override
            public int compare(Replacement left, Replacement right) {
                return Integer.compare(left.start, right.start);
            }
        });
        return replacements;
    }

    /**
     * 上下文行只负责定位，不重写它们；这样混合换行文件中的原始
     * 行结束符也能保留。
     */
    private static void addContentReplacements(
            List<Replacement> replacements,
            int found,
            List<String> oldLines,
            List<String> newLines,
            List<ApplyPatchParser.ContextIndex> contexts) {
        int oldStart = 0;
        int newStart = 0;
        for (ApplyPatchParser.ContextIndex context : contexts) {
            int oldContext = context.getOldIndex();
            int newContext = context.getNewIndex();
            if (oldContext >= oldLines.size() || newContext >= newLines.size()) {
                break;
            }
            if (oldStart != oldContext || newStart != newContext) {
                replacements.add(new Replacement(
                    found + oldStart,
                    oldContext - oldStart,
                    newLines.subList(newStart, newContext)
                ));
            }
            oldStart = oldContext + 1;
            newStart = newContext + 1;
        }
        if (oldStart != oldLines.size() || newStart != newLines.size()) {
            replacements.add(new Replacement(
                found + oldStart,
                oldLines.size() - oldStart,
                newLines.subList(newStart, newLines.size())
            ));
        }
    }

    private static int seek(List<String> lines,
                            List<String> pattern,
                            int start,
                            boolean endOfFile) {
        if (pattern.isEmpty()) return Math.min(start, lines.size());
        if (pattern.size() > lines.size()) return -1;
        int maximum = lines.size() - pattern.size();
        if (endOfFile && maximum < start) return -1;
        int searchStart = endOfFile ? maximum : Math.min(start, maximum + 1);
        int searchEnd = endOfFile ? maximum : maximum;
        int found = seek(lines, pattern, searchStart, searchEnd, Match.EXACT);
        if (found >= 0) return found;
        found = seek(lines, pattern, searchStart, searchEnd, Match.RIGHT_TRIMMED);
        if (found >= 0) return found;
        return seek(lines, pattern, searchStart, searchEnd, Match.TRIMMED);
    }

    private static int seek(List<String> lines,
                            List<String> pattern,
                            int start,
                            int end,
                            Match match) {
        if (start > end) return -1;
        for (int index = start; index <= end; index++) {
            boolean matched = true;
            for (int offset = 0; offset < pattern.size(); offset++) {
                if (!match.matches(
                        lines.get(index + offset), pattern.get(offset))) {
                    matched = false;
                    break;
                }
            }
            if (matched) return index;
        }
        return -1;
    }

    private void applyReplacements(List<Replacement> replacements) {
        // 从后向前修改，避免靠前的增删改变后续 replacement 的索引。
        for (int index = replacements.size() - 1; index >= 0; index--) {
            Replacement replacement = replacements.get(index);
            for (int count = 0; count < replacement.oldLength; count++) {
                lines.remove(replacement.start);
            }
            for (int offset = 0; offset < replacement.newLines.size(); offset++) {
                lines.add(
                    replacement.start + offset,
                    new SourceLine(
                        replacement.newLines.get(offset), preferredEnding
                    )
                );
            }
        }
        // Codex 的补丁语义会为更新后的最后一行补齐终止符。
        for (SourceLine line : lines) {
            if (line.ending == null) line.ending = preferredEnding;
        }
    }

    private String contents() {
        StringBuilder result = new StringBuilder();
        for (SourceLine line : lines) {
            result.append(line.text);
            if (line.ending != null) result.append(line.ending.text);
        }
        return result.toString();
    }

    private static String rightTrim(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static String join(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) joined.append('\n');
            joined.append(lines.get(index));
        }
        return joined.toString();
    }

    private static PatchContextException contextFailure(String message) {
        return new PatchContextException(message);
    }

    private enum Match {
        EXACT {
            @Override
            boolean matches(String actual, String expected) {
                return actual.equals(expected);
            }
        },
        RIGHT_TRIMMED {
            @Override
            boolean matches(String actual, String expected) {
                return rightTrim(actual).equals(rightTrim(expected));
            }
        },
        TRIMMED {
            @Override
            boolean matches(String actual, String expected) {
                return actual.trim().equals(expected.trim());
            }
        };

        abstract boolean matches(String actual, String expected);
    }

    private enum LineEnding {
        LF("\n"), CRLF("\r\n"), CR("\r");

        private final String text;

        LineEnding(String text) {
            this.text = text;
        }
    }

    private static final class SourceLine {
        private final String text;
        private LineEnding ending;

        private SourceLine(String text, LineEnding ending) {
            this.text = text;
            this.ending = ending;
        }
    }

    private static final class Replacement {
        private final int start;
        private final int oldLength;
        private final List<String> newLines;

        private Replacement(int start,
                            int oldLength,
                            List<String> newLines) {
            this.start = start;
            this.oldLength = oldLength;
            this.newLines = new ArrayList<String>(newLines);
        }
    }

    static final class PatchContextException extends IllegalArgumentException {
        private PatchContextException(String message) {
            super(message);
        }
    }
}
