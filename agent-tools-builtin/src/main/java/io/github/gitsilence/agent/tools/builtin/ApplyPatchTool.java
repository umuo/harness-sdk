package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.AbstractTool;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用 Codex 风格补丁协议执行有边界的多文件文本修改。 */
final class ApplyPatchTool extends AbstractTool<ApplyPatchTool.Input> {

    private final WorkspacePathResolver paths;
    private final FileObservationTracker observations;
    private final PathLocks locks;
    private final int maxPatchBytes;
    private final int maxPatchFiles;
    private final int maxPatchAffectedBytes;
    private final int maxWriteBytes;
    private final int maxEditableBytes;

    static Tool create(WorkspacePathResolver paths,
                       FileObservationTracker observations,
                       PathLocks locks,
                       int maxPatchBytes,
                       int maxPatchFiles,
                       int maxPatchAffectedBytes,
                       int maxWriteBytes,
                       int maxEditableBytes) {
        return new ApplyPatchTool(
            paths, observations, locks, maxPatchBytes, maxPatchFiles,
            maxPatchAffectedBytes, maxWriteBytes, maxEditableBytes
        );
    }

    private ApplyPatchTool(WorkspacePathResolver paths,
                           FileObservationTracker observations,
                           PathLocks locks,
                           int maxPatchBytes,
                           int maxPatchFiles,
                           int maxPatchAffectedBytes,
                           int maxWriteBytes,
                           int maxEditableBytes) {
        super(
            "apply_patch",
            "Apply one Codex-style patch across UTF-8 files. Supports "
                + "*** Add File, *** Delete File, *** Update File, optional "
                + "*** Move to, @@ context, and *** End of File. Read every "
                + "existing source or destination file before changing it.",
            Input.class
        );
        this.paths = paths;
        this.observations = observations;
        this.locks = locks;
        this.maxPatchBytes = maxPatchBytes;
        this.maxPatchFiles = maxPatchFiles;
        this.maxPatchAffectedBytes = maxPatchAffectedBytes;
        this.maxWriteBytes = maxWriteBytes;
        this.maxEditableBytes = maxEditableBytes;
    }

    @Override
    protected ToolResult execute(Input arguments, ToolContext context) {
        String patchText = arguments.patch;
        if (patchText == null) {
            throw failure(
                "PATCH_INVALID", "patch must not be null",
                "Provide a Codex-style patch beginning with *** Begin Patch.",
                null
            );
        }
        int patchBytes = patchText.getBytes(StandardCharsets.UTF_8).length;
        if (patchBytes > maxPatchBytes) {
            throw failure(
                "PATCH_TOO_LARGE",
                "Patch is " + patchBytes + " bytes; maximum is " + maxPatchBytes,
                "Split the change into smaller, focused patches.",
                null
            );
        }

        ApplyPatchParser.Patch patch;
        try {
            patch = ApplyPatchParser.parse(patchText);
        } catch (ApplyPatchParser.PatchSyntaxException error) {
            ToolErrorInfo info = ToolErrorInfo.builder(
                "PATCH_INVALID", "Invalid patch: " + error.getMessage()
            ).retryable(true)
                .recoveryHint(
                    "Fix the patch markers or hunk syntax, then retry the whole patch."
                )
                .detail("line", error.getLine())
                .build();
            throw new ToolFailureException(info, error);
        }

        List<ResolvedAction> actions = resolve(patch.getActions());
        List<Path> affectedPaths = uniquePaths(actions);
        if (affectedPaths.size() > maxPatchFiles) {
            throw failure(
                "PATCH_TOO_MANY_FILES",
                "Patch affects " + affectedPaths.size()
                    + " paths; maximum is " + maxPatchFiles,
                "Split the change into smaller patches.", null
            );
        }
        return withLocks(affectedPaths, 0, new LockedOperation() {
            @Override
            public ToolResult run() {
                return executeLocked(actions, context, patchBytes);
            }
        });
    }

    private ToolResult executeLocked(List<ResolvedAction> actions,
                                     ToolContext context,
                                     int patchBytes) {
        Map<Path, FileState> states = new LinkedHashMap<Path, FileState>();
        long affectedBytes = 0L;
        for (Path path : uniquePaths(actions)) {
            FileState state = load(path, context);
            states.put(path, state);
            affectedBytes = addToBudget(
                affectedBytes, state.originalBytes, path
            );
        }

        Map<Path, String> finalContents = new LinkedHashMap<Path, String>();
        List<String> summary = new ArrayList<String>();
        int writtenBytes = 0;
        for (ResolvedAction resolved : actions) {
            ApplyPatchParser.Action action = resolved.action;
            FileState source = states.get(resolved.path);
            if (action instanceof ApplyPatchParser.AddAction) {
                String content = ((ApplyPatchParser.AddAction) action).getContent();
                int bytes = utf8Length(content);
                requireSize(bytes, maxWriteBytes, resolved.path, "created file");
                affectedBytes = addToBudget(affectedBytes, bytes, resolved.path);
                finalContents.put(resolved.path, content);
                writtenBytes = safeAdd(writtenBytes, bytes);
                summary.add("A " + paths.display(resolved.path));
            } else if (action instanceof ApplyPatchParser.DeleteAction) {
                requireExisting(source, resolved.path, "delete");
                finalContents.put(resolved.path, null);
                summary.add("D " + paths.display(resolved.path));
            } else {
                requireExisting(source, resolved.path, "update");
                ApplyPatchParser.UpdateAction update =
                    (ApplyPatchParser.UpdateAction) action;
                String changed;
                try {
                    changed = PatchTextFile.apply(
                        source.originalContent,
                        update.getChunks(),
                        paths.display(resolved.path)
                    );
                } catch (PatchTextFile.PatchContextException error) {
                    throw new ToolFailureException(
                        ToolErrorInfo.builder(
                            "PATCH_CONTEXT_NOT_FOUND", error.getMessage()
                        ).retryable(true)
                            .recoveryHint(
                                "Re-read the file, copy more exact context, and retry "
                                    + "the whole patch."
                            )
                            .detail("path", resolved.path)
                            .build(),
                        error
                    );
                }
                int bytes = utf8Length(changed);
                requireSize(
                    bytes, maxEditableBytes, resolved.path, "updated file"
                );
                affectedBytes = addToBudget(affectedBytes, bytes, resolved.path);
                writtenBytes = safeAdd(writtenBytes, bytes);
                if (resolved.movePath == null) {
                    finalContents.put(resolved.path, changed);
                    summary.add("M " + paths.display(resolved.path));
                } else {
                    finalContents.put(resolved.path, null);
                    finalContents.put(resolved.movePath, changed);
                    summary.add(
                        "M " + paths.display(resolved.path) + " -> "
                            + paths.display(resolved.movePath)
                    );
                }
            }
        }

        // 所有语法、上下文、版本和大小检查都完成后才进入写盘阶段。
        verifyUnchanged(states);
        commit(finalContents, states);
        for (Map.Entry<Path, String> mutation : finalContents.entrySet()) {
            observations.record(
                context,
                mutation.getKey(),
                observations.capture(mutation.getKey())
            );
        }

        StringBuilder output = new StringBuilder(
            "Success. Updated the following files:\n"
        );
        for (String entry : summary) output.append(entry).append('\n');
        return ToolResult.success(output.toString())
            .withMetadata("actions", actions.size())
            .withMetadata("pathsAffected", finalContents.size())
            .withMetadata("patchBytes", patchBytes)
            .withMetadata("affectedBytes", affectedBytes)
            .withMetadata("bytesWritten", writtenBytes)
            .withMetadata("preflightValidated", true);
    }

    private List<ResolvedAction> resolve(
            List<ApplyPatchParser.Action> parsedActions) {
        List<ResolvedAction> resolved = new ArrayList<ResolvedAction>();
        Map<Path, String> claimed = new LinkedHashMap<Path, String>();
        for (ApplyPatchParser.Action action : parsedActions) {
            Path path = paths.resolve(action.getPath());
            claim(claimed, path, action.getPath());
            Path movePath = null;
            if (action instanceof ApplyPatchParser.UpdateAction) {
                String requested = ((ApplyPatchParser.UpdateAction) action)
                    .getMovePath();
                if (requested != null) {
                    movePath = paths.resolve(requested);
                    claim(claimed, movePath, requested);
                }
            }
            resolved.add(new ResolvedAction(action, path, movePath));
        }
        return resolved;
    }

    private static void claim(Map<Path, String> claimed,
                              Path path,
                              String requested) {
        String previous = claimed.put(path, requested);
        if (previous != null) {
            throw failure(
                "PATCH_PATH_CONFLICT",
                "Path is targeted more than once in one patch: " + requested,
                "Combine changes for each file into one hunk.", path
            );
        }
    }

    private List<Path> uniquePaths(List<ResolvedAction> actions) {
        Set<Path> unique = new LinkedHashSet<Path>();
        for (ResolvedAction action : actions) {
            unique.add(action.path);
            if (action.movePath != null) unique.add(action.movePath);
        }
        List<Path> paths = new ArrayList<Path>(unique);
        Collections.sort(paths, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.toString().compareTo(right.toString());
            }
        });
        return paths;
    }

    private FileState load(Path path, ToolContext context) {
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (!exists) {
            FileVersion version = observations.capture(path);
            observations.requireCurrent(context, path, version);
            return new FileState(false, null, 0, version);
        }
        if (!Files.isRegularFile(path)) {
            throw failure(
                "PATCH_NOT_A_REGULAR_FILE",
                "Patch path is not a regular file: " + paths.display(path),
                "Choose a regular UTF-8 text file.", path
            );
        }
        try {
            long size = Files.size(path);
            if (size > maxEditableBytes) {
                throw failure(
                    "PATCH_FILE_TOO_LARGE",
                    "File is " + size + " bytes; patch file limit is "
                        + maxEditableBytes,
                    "Use a specialized streaming tool or split the file.", path
                );
            }
            byte[] bytes = Files.readAllBytes(path);
            String content = decodeUtf8(bytes, path);
            FileVersion version = FileVersion.fromBytes(bytes);
            observations.requireCurrent(context, path, version);
            return new FileState(true, content, bytes.length, version);
        } catch (IOException error) {
            throw BuiltinToolErrors.io("read for patch", path, error);
        }
    }

    private void verifyUnchanged(Map<Path, FileState> states) {
        for (Map.Entry<Path, FileState> entry : states.entrySet()) {
            FileVersion current = observations.capture(entry.getKey());
            if (!current.equals(entry.getValue().originalVersion)) {
                throw failure(
                    "FILE_CHANGED_DURING_PATCH",
                    "File changed while the patch was being prepared: "
                        + paths.display(entry.getKey()),
                    "Re-read all affected files and retry the whole patch.",
                    entry.getKey()
                );
            }
        }
    }

    private void commit(Map<Path, String> finalContents,
                        Map<Path, FileState> originals) {
        try {
            // 先原子写入新增/更新文件，全部成功后再执行删除，
            // 以缩短不可恢复窗口。
            for (Map.Entry<Path, String> mutation : finalContents.entrySet()) {
                if (mutation.getValue() != null) {
                    AtomicFileWriter.writeUtf8(
                        mutation.getKey(), mutation.getValue()
                    );
                }
            }
            for (Map.Entry<Path, String> mutation : finalContents.entrySet()) {
                if (mutation.getValue() == null) {
                    Files.delete(mutation.getKey());
                }
            }
        } catch (IOException applyError) {
            IOException rollbackError = rollback(finalContents, originals);
            String code = rollbackError == null
                ? "PATCH_APPLY_FAILED" : "PATCH_ROLLBACK_FAILED";
            String message = "Could not apply patch: " + applyError.getMessage();
            if (rollbackError != null) {
                applyError.addSuppressed(rollbackError);
                message += "; rollback also failed: " + rollbackError.getMessage();
            }
            throw new ToolFailureException(
                ToolErrorInfo.builder(code, message)
                    .retryable(rollbackError == null)
                    .recoveryHint(
                        rollbackError == null
                            ? "The original files were restored; inspect and retry."
                            : "Inspect every affected path before making further changes."
                    )
                    .build(),
                applyError
            );
        }
    }

    private IOException rollback(Map<Path, String> finalContents,
                                 Map<Path, FileState> originals) {
        IOException first = null;
        for (Path path : finalContents.keySet()) {
            FileState original = originals.get(path);
            try {
                if (original.originalExists) {
                    AtomicFileWriter.writeUtf8(path, original.originalContent);
                } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    // 父路径可能本身是普通文件；此时 deleteIfExists 仍可能抛出
                    // “not a directory”。先确认补丁确实创建了目标再删除。
                    Files.delete(path);
                }
            } catch (IOException error) {
                if (first == null) first = error;
                else first.addSuppressed(error);
            }
        }
        return first;
    }

    private long addToBudget(long current, long addition, Path path) {
        long total = current + addition;
        if (total < current || total > maxPatchAffectedBytes) {
            throw failure(
                "PATCH_AFFECTED_BYTES_EXCEEDED",
                "Patch would inspect or produce more than "
                    + maxPatchAffectedBytes + " bytes",
                "Split the change into smaller patches.", path
            );
        }
        return total;
    }

    private static void requireExisting(FileState state,
                                        Path path,
                                        String operation) {
        if (!state.originalExists) {
            throw failure(
                "PATCH_FILE_NOT_FOUND",
                "Cannot " + operation + " missing file: " + path,
                "Check the patch path or add the file instead.", path
            );
        }
    }

    private static void requireSize(int bytes,
                                    int maximum,
                                    Path path,
                                    String kind) {
        if (bytes > maximum) {
            throw failure(
                "PATCH_RESULT_TOO_LARGE",
                "The " + kind + " would be " + bytes
                    + " bytes; maximum is " + maximum,
                "Reduce the result size or split the content.", path
            );
        }
    }

    private String decodeUtf8(byte[] bytes, Path path) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "PATCH_INVALID_UTF8",
                    "File is not valid UTF-8 text: " + paths.display(path)
                ).retryable(false)
                    .recoveryHint("Use a binary-aware tool or convert the file to UTF-8.")
                    .detail("path", path)
                    .build(),
                error
            );
        }
    }

    private ToolResult withLocks(List<Path> paths,
                                 int index,
                                 LockedOperation operation) {
        if (index >= paths.size()) return operation.run();
        synchronized (locks.forPath(paths.get(index))) {
            return withLocks(paths, index + 1, operation);
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int safeAdd(int left, int right) {
        long result = (long) left + (long) right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static ToolFailureException failure(String code,
                                                String message,
                                                String recovery,
                                                Path path) {
        return BuiltinToolErrors.failure(code, message, recovery, path);
    }

    static final class Input {
        @ToolParam(
            description = "Codex-style patch text beginning with *** Begin Patch"
        )
        public String patch;
    }

    private interface LockedOperation {
        ToolResult run();
    }

    private static final class ResolvedAction {
        private final ApplyPatchParser.Action action;
        private final Path path;
        private final Path movePath;

        private ResolvedAction(ApplyPatchParser.Action action,
                               Path path,
                               Path movePath) {
            this.action = action;
            this.path = path;
            this.movePath = movePath;
        }
    }

    private static final class FileState {
        private final boolean originalExists;
        private final String originalContent;
        private final int originalBytes;
        private final FileVersion originalVersion;

        private FileState(boolean originalExists,
                          String originalContent,
                          int originalBytes,
                          FileVersion originalVersion) {
            this.originalExists = originalExists;
            this.originalContent = originalContent;
            this.originalBytes = originalBytes;
            this.originalVersion = originalVersion;
        }
    }
}
