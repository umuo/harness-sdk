package io.github.gitsilence.agent.skill;

import java.nio.file.Path;

/** Indicates that a SKILL.md document is unreadable or invalid. */
public final class SkillLoadException extends IllegalArgumentException {

    private final Path path;

    public SkillLoadException(Path path, String message) {
        super(message);
        this.path = path;
    }

    public SkillLoadException(Path path, String message, Throwable cause) {
        super(message, cause);
        this.path = path;
    }

    public Path getPath() {
        return path;
    }
}
