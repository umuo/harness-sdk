package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/** 把模型提供的路径解析为受工作区边界约束的规范绝对路径。 */
final class WorkspacePathResolver {

    private final Path root;
    private final boolean allowOutsideWorkspace;
    private final Path readableOutputRoot;

    WorkspacePathResolver(Path root,
                          boolean allowOutsideWorkspace,
                          Path readableOutputRoot) {
        Objects.requireNonNull(root, "root");
        try {
            Path absolute = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(absolute)) {
                throw new IllegalArgumentException(
                    "workspace root must be an existing directory: " + absolute
                );
            }
            this.root = absolute.toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException(
                "Cannot resolve workspace root: " + root, error
            );
        }
        this.allowOutsideWorkspace = allowOutsideWorkspace;
        this.readableOutputRoot = readableOutputRoot == null
            ? null : readableOutputRoot.toAbsolutePath().normalize();
    }

    Path resolve(String input) {
        return resolve(input, false);
    }

    Path resolveReadable(String input) {
        return resolve(input, true);
    }

    private Path resolve(String input, boolean includeOutputRoot) {
        if (input == null || input.trim().isEmpty()) {
            throw failure(
                "INVALID_PATH", "file_path must be a non-empty string",
                "Provide a path relative to the workspace or an allowed absolute path."
            );
        }
        Path supplied = Paths.get(input);
        Path candidate = supplied.isAbsolute()
            ? supplied.toAbsolutePath().normalize()
            : root.resolve(supplied).normalize();
        try {
            // 对尚不存在的目标向上寻找现存祖先，再解析其真实路径；这样写入新文件时
            // 也能检测祖先目录中的符号链接是否跳出了工作区。
            Path existing = candidate;
            while (existing != null
                    && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                throw failure(
                    "PATH_NOT_RESOLVABLE",
                    "Cannot resolve path: " + candidate,
                    "Use a path whose parent directory exists."
                );
            }
            Path realExisting = existing.toRealPath();
            Path resolved = realExisting.resolve(existing.relativize(candidate)).normalize();
            if (!allowOutsideWorkspace
                    && !resolved.startsWith(root)
                    && !(includeOutputRoot && isReadableOutput(resolved))) {
                throw outside(resolved);
            }
            return resolved;
        } catch (IOException error) {
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "PATH_RESOLUTION_FAILED",
                    "Cannot resolve path '" + input + "': " + error.getMessage()
                ).retryable(true)
                    .recoveryHint("Check the path and workspace permissions, then retry.")
                    .detail("path", input)
                    .build(),
                error
            );
        }
    }

    private boolean isReadableOutput(Path path) throws IOException {
        // Tool 输出目录只额外授予 read_file 读取权，不授予编辑或 Bash 工作目录权限。
        if (readableOutputRoot == null) {
            return false;
        }
        Path rootPath = Files.exists(readableOutputRoot)
            ? readableOutputRoot.toRealPath()
            : readableOutputRoot;
        return path.startsWith(rootPath);
    }

    Path resolveDirectory(String input) {
        Path path = resolve(input == null ? "." : input);
        if (!Files.exists(path)) {
            throw failure(
                "DIRECTORY_NOT_FOUND", "Directory not found: " + display(path),
                "Choose an existing directory inside the workspace."
            );
        }
        if (!Files.isDirectory(path)) {
            throw failure(
                "NOT_A_DIRECTORY", "Path is not a directory: " + display(path),
                "Choose a directory path and retry."
            );
        }
        return path;
    }

    String display(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return normalized.toString();
        }
        String relative = root.relativize(normalized)
            .toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    Path getRoot() {
        return root;
    }

    private ToolFailureException outside(Path path) {
        return new ToolFailureException(
            ToolErrorInfo.builder(
                "PATH_OUTSIDE_WORKSPACE",
                "Path is outside the configured workspace: " + path
            ).retryable(true)
                .recoveryHint("Use a path inside " + root + ".")
                .detail("workspace", root)
                .detail("path", path)
                .build()
        );
    }

    private static ToolFailureException failure(String code,
                                                String message,
                                                String recovery) {
        return new ToolFailureException(
            ToolErrorInfo.builder(code, message)
                .retryable(true)
                .recoveryHint(recovery)
                .build()
        );
    }
}
