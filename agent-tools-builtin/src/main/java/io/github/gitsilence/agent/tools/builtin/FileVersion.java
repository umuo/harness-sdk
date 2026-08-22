package io.github.gitsilence.agent.tools.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

final class FileVersion {

    private static final FileVersion ABSENT = new FileVersion(false, new byte[0]);

    private final boolean present;
    private final byte[] sha256;

    private FileVersion(boolean present, byte[] sha256) {
        this.present = present;
        this.sha256 = sha256;
    }

    static FileVersion capture(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ABSENT;
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return present(digest.digest());
    }

    static FileVersion fromBytes(byte[] bytes) {
        MessageDigest digest = newDigest();
        digest.update(bytes);
        return present(digest.digest());
    }

    static FileVersion present(byte[] sha256) {
        return new FileVersion(true, sha256.clone());
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    boolean isPresent() {
        return present;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FileVersion)) return false;
        FileVersion that = (FileVersion) other;
        return present == that.present && Arrays.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return 31 * Boolean.valueOf(present).hashCode() + Arrays.hashCode(sha256);
    }
}
