package io.github.gitsilence.agent.skill;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable metadata for a file-backed Agent Skill.
 *
 * <p>The descriptor intentionally does not contain instructions or executable
 * Tool instances. Instructions remain in {@code SKILL.md} until the model
 * activates the Skill.</p>
 */
public final class Skill {

    private final String name;
    private final String description;
    private final Path rootDirectory;
    private final Path skillFile;
    private final String license;
    private final String compatibility;
    private final Map<String, String> metadata;
    private final List<String> allowedTools;

    Skill(String name,
          String description,
          Path rootDirectory,
          Path skillFile,
          String license,
          String compatibility,
          Map<String, String> metadata,
          List<String> allowedTools) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        this.skillFile = Objects.requireNonNull(skillFile, "skillFile");
        this.license = license;
        this.compatibility = compatibility;
        this.metadata = Collections.unmodifiableMap(
            new LinkedHashMap<String, String>(metadata)
        );
        this.allowedTools = Collections.unmodifiableList(
            new java.util.ArrayList<String>(allowedTools)
        );
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Path getRootDirectory() { return rootDirectory; }
    public Path getSkillFile() { return skillFile; }
    public String getLicense() { return license; }
    public String getCompatibility() { return compatibility; }
    public Map<String, String> getMetadata() { return metadata; }
    public List<String> getAllowedTools() { return allowedTools; }
}
