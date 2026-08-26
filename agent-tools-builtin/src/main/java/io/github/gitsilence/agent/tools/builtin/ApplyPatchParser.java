package io.github.gitsilence.agent.tools.builtin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析 Codex 风格的 {@code apply_patch} 文本协议。
 *
 * <p>该类只负责语法，不访问文件系统。路径边界、文件版本和写入事务由
 * {@link ApplyPatchTool} 统一处理，避免解析阶段产生任何副作用。</p>
 */
final class ApplyPatchParser {

    private static final String BEGIN_PATCH = "*** Begin Patch";
    private static final String END_PATCH = "*** End Patch";
    private static final String ADD_FILE = "*** Add File: ";
    private static final String DELETE_FILE = "*** Delete File: ";
    private static final String UPDATE_FILE = "*** Update File: ";
    private static final String MOVE_TO = "*** Move to: ";
    private static final String END_OF_FILE = "*** End of File";

    private ApplyPatchParser() {
    }

    static Patch parse(String patchText) {
        if (patchText == null) {
            throw syntax(0, "patch must not be null");
        }
        String normalized = patchText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int first = 0;
        int last = lines.length - 1;
        while (first <= last && lines[first].trim().isEmpty()) first++;
        while (last >= first && lines[last].trim().isEmpty()) last--;
        if (first > last || !BEGIN_PATCH.equals(lines[first].trim())) {
            throw syntax(first + 1,
                "the first line must be '*** Begin Patch'");
        }
        if (!END_PATCH.equals(lines[last].trim())) {
            throw syntax(last + 1,
                "the last line must be '*** End Patch'");
        }

        List<Action> actions = new ArrayList<Action>();
        int index = first + 1;
        while (index < last) {
            String header = lines[index].trim();
            if (header.isEmpty()) {
                throw syntax(index + 1, "unexpected blank line between file hunks");
            }
            if (header.startsWith(ADD_FILE)) {
                String path = pathAfter(header, ADD_FILE, index);
                AddAction add = new AddAction(path);
                index++;
                while (index < last && !isActionHeader(lines[index])) {
                    String line = lines[index];
                    if (!line.startsWith("+")) {
                        throw syntax(index + 1,
                            "every added-file line must start with '+'");
                    }
                    add.lines.add(line.substring(1));
                    index++;
                }
                if (add.lines.isEmpty()) {
                    throw syntax(index + 1,
                        "add-file hunk for '" + path + "' has no content lines");
                }
                actions.add(add);
                continue;
            }
            if (header.startsWith(DELETE_FILE)) {
                actions.add(new DeleteAction(
                    pathAfter(header, DELETE_FILE, index)
                ));
                index++;
                continue;
            }
            if (header.startsWith(UPDATE_FILE)) {
                UpdateAction update = new UpdateAction(
                    pathAfter(header, UPDATE_FILE, index)
                );
                index = parseUpdate(lines, index + 1, last, update);
                actions.add(update);
                continue;
            }
            throw syntax(index + 1,
                "invalid hunk header; expected Add File, Delete File, or Update File");
        }
        if (actions.isEmpty()) {
            throw syntax(first + 2, "patch does not modify any files");
        }
        return new Patch(actions);
    }

    private static int parseUpdate(String[] lines,
                                   int index,
                                   int last,
                                   UpdateAction update) {
        Chunk current = null;
        while (index < last && !isActionHeader(lines[index])) {
            String raw = lines[index];
            String trimmed = raw.trim();
            if (trimmed.startsWith(MOVE_TO)) {
                if (update.movePath != null || current != null) {
                    throw syntax(index + 1,
                        "Move to must appear once, before update chunks");
                }
                update.movePath = pathAfter(trimmed, MOVE_TO, index);
                index++;
                continue;
            }
            if ("@@".equals(trimmed) || trimmed.startsWith("@@ ")) {
                requireNonEmpty(current, index, update.getPath());
                String context = "@@".equals(trimmed)
                    ? null : trimmed.substring(3);
                current = new Chunk(context);
                update.chunks.add(current);
                index++;
                continue;
            }
            if (END_OF_FILE.equals(trimmed)) {
                if (current == null) {
                    throw syntax(index + 1,
                        "End of File must follow an update chunk");
                }
                requireNonEmpty(current, index, update.getPath());
                current.endOfFile = true;
                index++;
                continue;
            }
            if (current == null) {
                current = new Chunk(null);
                update.chunks.add(current);
            }
            if (raw.isEmpty()) {
                current.addContext("");
            } else if (raw.charAt(0) == ' ') {
                current.addContext(raw.substring(1));
            } else if (raw.charAt(0) == '+') {
                current.newLines.add(raw.substring(1));
            } else if (raw.charAt(0) == '-') {
                current.oldLines.add(raw.substring(1));
            } else {
                throw syntax(index + 1,
                    "update lines must start with ' ', '+', '-', or '@@'");
            }
            index++;
        }
        requireNonEmpty(current, index, update.getPath());
        if (update.chunks.isEmpty()) {
            throw syntax(index + 1,
                "update-file hunk for '" + update.getPath() + "' is empty");
        }
        return index;
    }

    private static void requireNonEmpty(Chunk chunk, int index, String path) {
        if (chunk != null && chunk.oldLines.isEmpty() && chunk.newLines.isEmpty()) {
            throw syntax(index + 1,
                "update chunk for '" + path + "' does not contain any lines");
        }
    }

    private static boolean isActionHeader(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith(ADD_FILE)
            || trimmed.startsWith(DELETE_FILE)
            || trimmed.startsWith(UPDATE_FILE);
    }

    private static String pathAfter(String line, String marker, int index) {
        String path = line.substring(marker.length()).trim();
        if (path.isEmpty()) {
            throw syntax(index + 1, "file path must not be empty");
        }
        return path;
    }

    private static PatchSyntaxException syntax(int line, String message) {
        return new PatchSyntaxException(line, message);
    }

    static final class Patch {
        private final List<Action> actions;

        private Patch(List<Action> actions) {
            this.actions = Collections.unmodifiableList(
                new ArrayList<Action>(actions)
            );
        }

        List<Action> getActions() {
            return actions;
        }
    }

    abstract static class Action {
        private final String path;

        private Action(String path) {
            this.path = path;
        }

        String getPath() {
            return path;
        }
    }

    static final class AddAction extends Action {
        private final List<String> lines = new ArrayList<String>();

        private AddAction(String path) {
            super(path);
        }

        String getContent() {
            return joinWithTrailingNewline(lines);
        }
    }

    static final class DeleteAction extends Action {
        private DeleteAction(String path) {
            super(path);
        }
    }

    static final class UpdateAction extends Action {
        private String movePath;
        private final List<Chunk> chunks = new ArrayList<Chunk>();

        private UpdateAction(String path) {
            super(path);
        }

        String getMovePath() {
            return movePath;
        }

        List<Chunk> getChunks() {
            return Collections.unmodifiableList(chunks);
        }
    }

    static final class Chunk {
        private final String context;
        private final List<String> oldLines = new ArrayList<String>();
        private final List<String> newLines = new ArrayList<String>();
        private final List<ContextIndex> contextIndices =
            new ArrayList<ContextIndex>();
        private boolean endOfFile;

        private Chunk(String context) {
            this.context = context;
        }

        private void addContext(String line) {
            contextIndices.add(new ContextIndex(
                oldLines.size(), newLines.size()
            ));
            oldLines.add(line);
            newLines.add(line);
        }

        String getContext() {
            return context;
        }

        List<String> getOldLines() {
            return oldLines;
        }

        List<String> getNewLines() {
            return newLines;
        }

        List<ContextIndex> getContextIndices() {
            return contextIndices;
        }

        boolean isEndOfFile() {
            return endOfFile;
        }
    }

    static final class ContextIndex {
        private final int oldIndex;
        private final int newIndex;

        private ContextIndex(int oldIndex, int newIndex) {
            this.oldIndex = oldIndex;
            this.newIndex = newIndex;
        }

        int getOldIndex() {
            return oldIndex;
        }

        int getNewIndex() {
            return newIndex;
        }
    }

    static final class PatchSyntaxException extends IllegalArgumentException {
        private final int line;

        private PatchSyntaxException(int line, String message) {
            super((line > 0 ? "line " + line + ": " : "") + message);
            this.line = line;
        }

        int getLine() {
            return line;
        }
    }

    private static String joinWithTrailingNewline(List<String> lines) {
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(line).append('\n');
        }
        return content.toString();
    }
}
