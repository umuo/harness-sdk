package io.github.gitsilence.agent.agent;

import java.util.Objects;

public final class AgentDescriptor {

    private final String name;
    private final String description;

    public AgentDescriptor(String name, String description) {
        this.name = requireText(name, "name");
        this.description = requireText(description, "description");
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
