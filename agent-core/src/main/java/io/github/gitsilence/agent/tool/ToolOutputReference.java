package io.github.gitsilence.agent.tool;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A location from which content omitted from a Tool preview can be recovered.
 * References on one result must collectively cover the producer's omitted
 * content; the final result policy treats their presence as a no-copy signal.
 */
public final class ToolOutputReference {

    public enum Kind {
        SOURCE_FILE,
        TEMPORARY_FILE
    }

    private final Kind kind;
    private final String path;
    private final String instruction;

    private ToolOutputReference(Kind kind, Path path, String instruction) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.path = Objects.requireNonNull(path, "path")
            .toAbsolutePath().normalize().toString();
        this.instruction = instruction == null ? "" : instruction.trim();
    }

    public static ToolOutputReference sourceFile(Path path, String instruction) {
        return new ToolOutputReference(Kind.SOURCE_FILE, path, instruction);
    }

    public static ToolOutputReference temporaryFile(Path path, String instruction) {
        return new ToolOutputReference(Kind.TEMPORARY_FILE, path, instruction);
    }

    public Kind getKind() {
        return kind;
    }

    public String getPath() {
        return path;
    }

    public String getInstruction() {
        return instruction;
    }
}
