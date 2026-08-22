package io.github.gitsilence.agent.tools.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    static void writeUtf8(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Path has no parent directory: " + path);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".agent-write-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
