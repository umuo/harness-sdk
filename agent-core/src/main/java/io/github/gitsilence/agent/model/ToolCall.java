package io.github.gitsilence.agent.model;

import java.util.Objects;

public final class ToolCall {

    private final String id;
    private final String name;
    private final String arguments;

    public ToolCall(String id, String name, String arguments) {
        this.id = requireText(id, "id");
        this.name = requireText(name, "name");
        this.arguments = arguments == null || arguments.trim().isEmpty() ? "{}" : arguments;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArguments() {
        return arguments;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
