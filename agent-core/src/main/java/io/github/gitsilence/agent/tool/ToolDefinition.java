package io.github.gitsilence.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ToolDefinition {

    private static final Pattern NAME_PATTERN =
        Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");

    private final String name;
    private final String description;
    private final String inputSchema;
    private final Map<String, Object> metadata;

    private ToolDefinition(Builder builder) {
        this.name = validateName(builder.name);
        this.description = requireText(builder.description, "description");
        this.inputSchema = validateSchema(builder.inputSchema);
        this.metadata = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(builder.metadata)
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    private static String validateName(String name) {
        requireText(name, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Tool name must match " + NAME_PATTERN.pattern() + ": " + name
            );
        }
        return name;
    }

    private static String validateSchema(String schema) {
        requireText(schema, "inputSchema");
        JsonNode node = JsonSupport.readTree(schema);
        if (!node.isObject()) {
            throw new IllegalArgumentException("Tool input schema must be a JSON object");
        }
        return schema;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private String name;
        private String description;
        private String inputSchema = "{\"type\":\"object\",\"properties\":{}}";
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder inputSchema(String inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Builder metadata(String name, Object value) {
            this.metadata.put(name, value);
            return this;
        }

        public ToolDefinition build() {
            return new ToolDefinition(this);
        }
    }
}
