package io.github.gitsilence.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

public final class ToolArguments {

    private final String rawJson;
    private final JsonNode root;

    private ToolArguments(String rawJson) {
        this.rawJson = rawJson == null || rawJson.trim().isEmpty() ? "{}" : rawJson;
        this.root = JsonSupport.readTree(this.rawJson);
        if (!root.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
    }

    public static ToolArguments parse(String json) {
        return new ToolArguments(json);
    }

    public String rawJson() {
        return rawJson;
    }

    public String requireString(String name) {
        JsonNode value = require(name);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Argument '" + name + "' must be a string");
        }
        return value.asText();
    }

    public Optional<String> optionalString(String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Argument '" + name + "' must be a string");
        }
        return Optional.of(value.asText());
    }

    public int requireInt(String name) {
        JsonNode value = require(name);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("Argument '" + name + "' must be an integer");
        }
        return value.intValue();
    }

    public Optional<Integer> optionalInt(String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(
                "Argument '" + name + "' must be an integer"
            );
        }
        return Optional.of(value.intValue());
    }

    public boolean requireBoolean(String name) {
        JsonNode value = require(name);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("Argument '" + name + "' must be a boolean");
        }
        return value.booleanValue();
    }

    public Optional<Boolean> optionalBoolean(String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(
                "Argument '" + name + "' must be a boolean"
            );
        }
        return Optional.of(value.booleanValue());
    }

    public <T> T as(Class<T> type) {
        try {
            return JsonSupport.MAPPER.treeToValue(root, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Cannot convert tool arguments to " + type.getName() + ": " + e.getMessage(),
                e
            );
        }
    }

    JsonNode node(String name) {
        return root.get(name);
    }

    private JsonNode require(String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return value;
    }
}
