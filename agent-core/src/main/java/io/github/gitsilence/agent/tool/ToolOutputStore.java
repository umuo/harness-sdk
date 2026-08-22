package io.github.gitsilence.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Stores complete Tool output outside model context. POSIX stores request
 * owner-only directory and file permissions.
 */
public final class ToolOutputStore {

    private static final String DEFAULT_DIRECTORY_NAME = "agent-sdk-tool-output";
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        );
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );

    private final Path directory;

    public ToolOutputStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
    }

    public static ToolOutputStore systemTemporary() {
        return new ToolOutputStore(defaultDirectory());
    }

    public static Path defaultDirectory() {
        String temporary = System.getProperty("java.io.tmpdir");
        if (temporary == null || temporary.trim().isEmpty()) {
            throw new IllegalStateException("java.io.tmpdir is not configured");
        }
        return Paths.get(temporary, DEFAULT_DIRECTORY_NAME)
            .toAbsolutePath().normalize();
    }

    public Path writeUtf8(String prefix, String content) throws IOException {
        Objects.requireNonNull(content, "content");
        Path path = createFile(prefix, ".log");
        boolean complete = false;
        try {
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            complete = true;
            return path;
        } finally {
            if (!complete) {
                Files.deleteIfExists(path);
            }
        }
    }

    public Path createFile(String prefix, String suffix) throws IOException {
        Path realDirectory = ensureDirectory();
        String safePrefix = safePrefix(prefix);
        try {
            FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(OWNER_FILE_PERMISSIONS);
            return Files.createTempFile(
                realDirectory, safePrefix, suffix, permissions
            ).toAbsolutePath().normalize();
        } catch (UnsupportedOperationException unsupported) {
            return Files.createTempFile(realDirectory, safePrefix, suffix)
                .toAbsolutePath().normalize();
        }
    }

    public Path getDirectory() {
        return directory;
    }

    private Path ensureDirectory() throws IOException {
        try {
            FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY_PERMISSIONS);
            Files.createDirectories(directory, permissions);
        } catch (UnsupportedOperationException unsupported) {
            Files.createDirectories(directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("Tool output path is not a directory: " + directory);
        }
        return directory.toRealPath();
    }

    private static String safePrefix(String value) {
        String candidate = value == null ? "tool-output-" : value;
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "-");
        while (candidate.length() < 3) {
            candidate += "-";
        }
        return candidate;
    }
}
