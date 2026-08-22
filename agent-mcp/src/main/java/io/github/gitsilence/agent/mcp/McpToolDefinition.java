package io.github.gitsilence.agent.mcp;

import java.util.Objects;
import java.util.regex.Pattern;

/** A server Tool definition before adapting its name to the local Tool rules. */
public final class McpToolDefinition {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private final String name;
    private final String title;
    private final String description;
    private final String inputSchema;
    private final String outputSchema;
    private final String rawDefinitionJson;
    private final String taskSupport;

    public McpToolDefinition(String name,
                             String title,
                             String description,
                             String inputSchema,
                             String outputSchema,
                             String rawDefinitionJson) {
        this(
            name, title, description, inputSchema, outputSchema,
            rawDefinitionJson, ""
        );
    }

    public McpToolDefinition(String name,
                             String title,
                             String description,
                             String inputSchema,
                             String outputSchema,
                             String rawDefinitionJson,
                             String taskSupport) {
        this.name = validateName(name);
        this.title = optional(title);
        this.description = optional(description);
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        this.outputSchema = optional(outputSchema);
        this.rawDefinitionJson = Objects.requireNonNull(
            rawDefinitionJson, "rawDefinitionJson"
        );
        this.taskSupport = validateTaskSupport(taskSupport);
    }

    public String getName() { return name; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getInputSchema() { return inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public String getRawDefinitionJson() { return rawDefinitionJson; }
    public String getTaskSupport() { return taskSupport; }

    private static String validateName(String value) {
        Objects.requireNonNull(value, "tool name");
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "MCP tool name must match " + NAME.pattern() + ": " + value
            );
        }
        return value;
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }

    private static String validateTaskSupport(String value) {
        String candidate = optional(value);
        if (!candidate.isEmpty()
                && !"forbidden".equals(candidate)
                && !"optional".equals(candidate)
                && !"required".equals(candidate)) {
            throw new IllegalArgumentException(
                "Unknown MCP execution.taskSupport value: " + candidate
            );
        }
        return candidate;
    }
}
