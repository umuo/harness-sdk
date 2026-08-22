package io.github.gitsilence.agent.skill;

import io.github.gitsilence.agent.tool.AbstractTool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
import io.github.gitsilence.agent.tool.ToolOutputReference;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Loads registered Skill instructions and referenced text resources on demand. */
public final class SkillLoadTool extends AbstractTool<SkillLoadTool.Input> {

    public static final int DEFAULT_MAX_BYTES = 512 * 1024;

    private final SkillRegistry registry;
    private final int maxBytes;

    public SkillLoadTool(SkillRegistry registry) {
        this(registry, DEFAULT_MAX_BYTES);
    }

    public SkillLoadTool(SkillRegistry registry, int maxBytes) {
        super(
            "skill_load",
            "Loads an Agent Skill on demand. Supply its name to load SKILL.md "
                + "instructions; optionally supply a relative resource path to load "
                + "a referenced text file from that Skill.",
            Input.class
        );
        if (registry == null) throw new NullPointerException("registry");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.registry = registry;
        this.maxBytes = maxBytes;
    }

    @Override
    protected ToolResult execute(Input input, ToolContext context) {
        Skill skill = registry.find(input.name).orElseThrow(
            () -> unknownSkill(input.name)
        );
        String requested = normalizeResource(input.resource);
        boolean instructions = requested == null;
        Path target = instructions
            ? skill.getSkillFile()
            : resolveResource(skill, requested);
        String content = readText(target, requested, skill);
        if (instructions) {
            try {
                content = SkillLoader.body(content, target);
            } catch (SkillLoadException error) {
                throw invalidDocument(skill, target, error);
            }
        }

        String relative = instructions ? "SKILL.md" : requested;
        StringBuilder output = new StringBuilder()
            .append("Loaded Agent Skill '")
            .append(skill.getName())
            .append("' resource '")
            .append(relative)
            .append("'.\nSkill root: ")
            .append(skill.getRootDirectory())
            .append("\nResolve referenced paths relative to that root.\n\n")
            .append(instructions ? "<skill_instructions>\n" : "<skill_resource>\n")
            .append(content);
        if (!content.endsWith("\n")) output.append('\n');
        output.append(instructions
            ? "</skill_instructions>" : "</skill_resource>");

        return ToolResult.success(output.toString())
            .withMetadata("skill", skill.getName())
            .withMetadata("resource", relative)
            .withMetadata("path", target.toString())
            .withOutputReference(ToolOutputReference.sourceFile(
                target,
                "complete registered Skill source"
            ));
    }

    private Path resolveResource(Skill skill, String resource) {
        final Path relative;
        try {
            relative = Paths.get(resource);
        } catch (RuntimeException error) {
            throw invalidResource(skill, resource, "Invalid resource path", error);
        }
        if (relative.isAbsolute()) {
            throw outsideRoot(skill, resource);
        }
        Path candidate = skill.getRootDirectory().resolve(relative).normalize();
        if (!candidate.startsWith(skill.getRootDirectory())) {
            throw outsideRoot(skill, resource);
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(skill.getRootDirectory())) {
                throw outsideRoot(skill, resource);
            }
            if (!Files.isRegularFile(real)) {
                throw invalidResource(
                    skill, resource, "Skill resource is not a regular file", null
                );
            }
            return real;
        } catch (NoSuchFileException error) {
            throw missingResource(skill, resource, error);
        } catch (IOException error) {
            throw invalidResource(
                skill, resource, "Cannot resolve Skill resource", error
            );
        }
    }

    private String readText(Path path, String resource, Skill skill) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
            Math.min(maxBytes, 16 * 1024)
        );
        byte[] buffer = new byte[8192];
        int total = 0;
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw tooLarge(skill, resource, path);
                }
                output.write(buffer, 0, read);
            }
        } catch (ToolFailureException error) {
            throw error;
        } catch (IOException error) {
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "SKILL_READ_FAILED", "Cannot read Skill content: " + path
                ).retryable(false)
                    .recoveryHint("Check the Skill installation and file permissions.")
                    .detail("skill", skill.getName())
                    .detail("path", path)
                    .build(),
                error
            );
        }
        byte[] bytes = output.toByteArray();
        for (byte value : bytes) {
            if (value == 0) {
                throw notText(skill, resource, path, null);
            }
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw notText(skill, resource, path, error);
        }
    }

    private ToolFailureException unknownSkill(String name) {
        List<String> available = new ArrayList<String>();
        for (Skill skill : registry.definitions()) {
            available.add(skill.getName());
        }
        return new ToolFailureException(
            ToolErrorInfo.builder(
                "SKILL_NOT_FOUND", "Unknown Agent Skill: " + name
            ).retryable(true)
                .recoveryHint("Use a name from the available_skills list.")
                .detail("availableSkills", available)
                .build()
        );
    }

    private static String normalizeResource(String resource) {
        if (resource == null) return null;
        String normalized = resource.trim();
        if (normalized.isEmpty()) {
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "SKILL_INVALID_RESOURCE",
                    "resource must be omitted or contain a relative path"
                ).retryable(true)
                    .recoveryHint(
                        "Omit resource to load SKILL.md, or provide a relative file path."
                    )
                    .build()
            );
        }
        return normalized;
    }

    private static ToolFailureException outsideRoot(Skill skill, String resource) {
        return new ToolFailureException(
            ToolErrorInfo.builder(
                "SKILL_RESOURCE_OUTSIDE_ROOT",
                "Skill resource escapes its registered root: " + resource
            ).retryable(true)
                .recoveryHint("Use a relative path referenced by the loaded SKILL.md.")
                .detail("skill", skill.getName())
                .detail("resource", resource)
                .build()
        );
    }

    private static ToolFailureException missingResource(Skill skill,
                                                        String resource,
                                                        Throwable cause) {
        return new ToolFailureException(
            ToolErrorInfo.builder(
                "SKILL_RESOURCE_NOT_FOUND",
                "Skill resource does not exist: " + resource
            ).retryable(true)
                .recoveryHint("Check the paths referenced by SKILL.md and retry.")
                .detail("skill", skill.getName())
                .detail("resource", resource)
                .build(),
            cause
        );
    }

    private static ToolFailureException invalidResource(Skill skill,
                                                        String resource,
                                                        String message,
                                                        Throwable cause) {
        return new ToolFailureException(
            ToolErrorInfo.builder("SKILL_INVALID_RESOURCE", message + ": " + resource)
                .retryable(true)
                .recoveryHint("Use a readable text file beneath the Skill root.")
                .detail("skill", skill.getName())
                .detail("resource", resource)
                .build(),
            cause
        );
    }

    private ToolFailureException tooLarge(Skill skill,
                                          String resource,
                                          Path path) {
        return new ToolFailureException(
            ToolErrorInfo.builder(
                "SKILL_CONTENT_TOO_LARGE",
                "Skill content exceeds the " + maxBytes + " byte limit: " + path
            ).retryable(false)
                .recoveryHint("Split the Skill into smaller focused reference files.")
                .detail("skill", skill.getName())
                .detail("resource", resource == null ? "SKILL.md" : resource)
                .detail("maxBytes", maxBytes)
                .build()
        );
    }

    private static ToolFailureException notText(Skill skill,
                                                String resource,
                                                Path path,
                                                Throwable cause) {
        return new ToolFailureException(
            ToolErrorInfo.builder(
                "SKILL_RESOURCE_NOT_TEXT",
                "Skill resource is not valid UTF-8 text: " + path
            ).retryable(false)
                .recoveryHint(
                    "Reference binary assets by path instead of loading them into context."
                )
                .detail("skill", skill.getName())
                .detail("resource", resource == null ? "SKILL.md" : resource)
                .build(),
            cause
        );
    }

    private static ToolFailureException invalidDocument(Skill skill,
                                                        Path path,
                                                        Throwable cause) {
        return new ToolFailureException(
            ToolErrorInfo.builder(
                "SKILL_DOCUMENT_INVALID",
                "Registered SKILL.md is no longer valid: " + path
            ).retryable(false)
                .recoveryHint("Repair and reload the Skill before using it again.")
                .detail("skill", skill.getName())
                .detail("path", path)
                .build(),
            cause
        );
    }

    public static final class Input {
        @ToolParam(description = "Agent Skill name from available_skills")
        private String name;

        @ToolParam(
            description = "Optional path relative to the Skill root; omit for SKILL.md",
            required = false
        )
        private String resource;
    }
}
