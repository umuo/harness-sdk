package io.github.gitsilence.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads and discovers Agent Skills backed by standard SKILL.md documents. */
public final class SkillLoader {

    private static final String FILE_NAME = "SKILL.md";
    private static final int MAX_FRONTMATTER_CHARS = 64 * 1024;
    private static final Pattern NAME = Pattern.compile(
        "[a-z0-9]+(?:-[a-z0-9]+)*"
    );
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private SkillLoader() {
    }

    /** Loads one Skill from either its directory or its SKILL.md path. */
    public static Skill load(Path path) {
        if (path == null) {
            throw new NullPointerException("path");
        }
        Path candidate = path.toAbsolutePath().normalize();
        if (Files.isDirectory(candidate)) {
            candidate = candidate.resolve(FILE_NAME);
        }
        if (!FILE_NAME.equals(String.valueOf(candidate.getFileName()))) {
            throw new SkillLoadException(
                candidate, "Skill entry file must be named " + FILE_NAME
            );
        }
        try {
            if (!Files.isRegularFile(candidate)) {
                throw new SkillLoadException(
                    candidate, "Skill file does not exist or is not a regular file: "
                        + candidate
                );
            }
            Path skillFile = candidate.toRealPath();
            Path root = skillFile.getParent().toRealPath();
            JsonNode frontmatter = parseFrontmatter(skillFile);
            String name = requiredText(frontmatter, "name", skillFile);
            String description = requiredText(frontmatter, "description", skillFile);
            validateName(name, root, skillFile);
            if (description.length() > 1024) {
                throw invalid(skillFile, "description must not exceed 1024 characters");
            }
            String license = optionalText(frontmatter, "license", skillFile);
            String compatibility = optionalText(
                frontmatter, "compatibility", skillFile
            );
            if (compatibility != null && compatibility.length() > 500) {
                throw invalid(skillFile, "compatibility must not exceed 500 characters");
            }
            return new Skill(
                name,
                description,
                root,
                skillFile,
                license,
                compatibility,
                metadata(frontmatter.get("metadata"), skillFile),
                allowedTools(frontmatter.get("allowed-tools"), skillFile)
            );
        } catch (SkillLoadException error) {
            throw error;
        } catch (IOException error) {
            throw new SkillLoadException(
                candidate, "Cannot read Skill: " + candidate, error
            );
        }
    }

    /**
     * Recursively discovers SKILL.md files. Invalid entries are returned as
     * diagnostics so one bad package does not hide valid neighboring Skills.
     */
    public static Discovery discover(Path root) {
        if (root == null) {
            throw new NullPointerException("root");
        }
        final Path normalized = root.toAbsolutePath().normalize();
        final List<Path> files = new ArrayList<Path>();
        final List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        if (Files.isRegularFile(normalized)) {
            files.add(normalized);
        } else if (Files.isDirectory(normalized)) {
            try {
                Files.walkFileTree(normalized, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file,
                                                     BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()
                                && FILE_NAME.equals(String.valueOf(file.getFileName()))) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        diagnostics.add(new Diagnostic(
                            file, "Cannot inspect path: " + error.getMessage()
                        ));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException error) {
                diagnostics.add(new Diagnostic(
                    normalized, "Cannot scan Skill directory: " + error.getMessage()
                ));
            }
        } else {
            diagnostics.add(new Diagnostic(
                normalized, "Skill path does not exist: " + normalized
            ));
        }

        Collections.sort(files, Comparator.comparing(Path::toString));
        List<Skill> skills = new ArrayList<Skill>();
        for (Path file : files) {
            try {
                skills.add(load(file));
            } catch (SkillLoadException error) {
                diagnostics.add(new Diagnostic(error.getPath(), error.getMessage()));
            }
        }
        return new Discovery(skills, diagnostics);
    }

    static String body(String document, Path path) {
        String normalized = stripBom(document);
        int firstLineEnd = normalized.indexOf('\n');
        String first = firstLineEnd < 0
            ? normalized : normalized.substring(0, firstLineEnd);
        if (!"---".equals(stripCarriageReturn(first))) {
            throw invalid(path, "SKILL.md must begin with YAML frontmatter");
        }
        int cursor = firstLineEnd < 0 ? normalized.length() : firstLineEnd + 1;
        while (cursor <= normalized.length()) {
            int end = normalized.indexOf('\n', cursor);
            int next = end < 0 ? normalized.length() : end;
            String line = stripCarriageReturn(normalized.substring(cursor, next));
            if ("---".equals(line)) {
                int bodyStart = end < 0 ? normalized.length() : end + 1;
                return normalized.substring(bodyStart);
            }
            if (end < 0) break;
            cursor = end + 1;
        }
        throw invalid(path, "SKILL.md frontmatter is missing its closing ---");
    }

    private static JsonNode parseFrontmatter(Path path) throws IOException {
        StringBuilder yaml = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null || !"---".equals(stripBom(first))) {
                throw invalid(path, "SKILL.md must begin with YAML frontmatter");
            }
            boolean closed = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if ("---".equals(line)) {
                    closed = true;
                    break;
                }
                yaml.append(line).append('\n');
                if (yaml.length() > MAX_FRONTMATTER_CHARS) {
                    throw invalid(
                        path, "SKILL.md frontmatter exceeds "
                            + MAX_FRONTMATTER_CHARS + " characters"
                    );
                }
            }
            if (!closed) {
                throw invalid(path, "SKILL.md frontmatter is missing its closing ---");
            }
        }
        final JsonNode parsed;
        try {
            parsed = YAML.readTree(yaml.toString());
        } catch (IOException error) {
            throw new SkillLoadException(path, "Invalid YAML frontmatter", error);
        }
        if (parsed == null || !parsed.isObject()) {
            throw invalid(path, "SKILL.md frontmatter must be a YAML mapping");
        }
        return parsed;
    }

    private static void validateName(String name, Path root, Path path) {
        if (name.length() > 64 || !NAME.matcher(name).matches()) {
            throw invalid(
                path,
                "name must contain 1-64 lowercase letters, numbers, or single hyphens"
            );
        }
        String directoryName = String.valueOf(root.getFileName());
        if (!name.equals(directoryName)) {
            throw invalid(
                path, "name '" + name + "' must match directory '"
                    + directoryName + "'"
            );
        }
    }

    private static String requiredText(JsonNode root, String field, Path path) {
        String value = optionalText(root, field, path);
        if (value == null || value.trim().isEmpty()) {
            throw invalid(path, field + " must be a non-empty string");
        }
        return value;
    }

    private static String optionalText(JsonNode root, String field, Path path) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw invalid(path, field + " must be a string");
        }
        return value.asText();
    }

    private static Map<String, String> metadata(JsonNode node, Path path) {
        if (node == null || node.isNull()) {
            return Collections.emptyMap();
        }
        if (!node.isObject()) {
            throw invalid(path, "metadata must be a string-to-string mapping");
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw invalid(
                    path, "metadata value for '" + field.getKey() + "' must be a string"
                );
            }
            metadata.put(field.getKey(), field.getValue().asText());
        }
        return metadata;
    }

    private static List<String> allowedTools(JsonNode node, Path path) {
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }
        if (!node.isTextual()) {
            throw invalid(path, "allowed-tools must be a space-delimited string");
        }
        String value = node.asText().trim();
        if (value.isEmpty()) return Collections.emptyList();
        Set<String> unique = new LinkedHashSet<String>();
        Collections.addAll(unique, value.split("\\s+"));
        return new ArrayList<String>(unique);
    }

    private static SkillLoadException invalid(Path path, String message) {
        return new SkillLoadException(path, message + ": " + path);
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\ufeff'
            ? value.substring(1) : value;
    }

    private static String stripCarriageReturn(String value) {
        return value.endsWith("\r")
            ? value.substring(0, value.length() - 1) : value;
    }

    public static final class Discovery {
        private final List<Skill> skills;
        private final List<Diagnostic> diagnostics;

        private Discovery(List<Skill> skills, List<Diagnostic> diagnostics) {
            this.skills = Collections.unmodifiableList(new ArrayList<Skill>(skills));
            this.diagnostics = Collections.unmodifiableList(
                new ArrayList<Diagnostic>(diagnostics)
            );
        }

        public List<Skill> getSkills() { return skills; }
        public List<Diagnostic> getDiagnostics() { return diagnostics; }
        public boolean hasDiagnostics() { return !diagnostics.isEmpty(); }
    }

    public static final class Diagnostic {
        private final Path path;
        private final String message;

        private Diagnostic(Path path, String message) {
            this.path = path;
            this.message = message;
        }

        public Path getPath() { return path; }
        public String getMessage() { return message; }
    }
}
