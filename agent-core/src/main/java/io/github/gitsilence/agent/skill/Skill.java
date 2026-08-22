package io.github.gitsilence.agent.skill;

import io.github.gitsilence.agent.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Skill {

    private final String name;
    private final String instructions;
    private final List<Tool> tools;
    private final Map<String, Object> metadata;

    private Skill(Builder builder) {
        this.name = requireText(builder.name, "name");
        this.instructions = builder.instructions == null ? "" : builder.instructions;
        this.tools = Collections.unmodifiableList(new ArrayList<Tool>(builder.tools));
        this.metadata = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(builder.metadata)
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() { return name; }
    public String getInstructions() { return instructions; }
    public List<Tool> getTools() { return tools; }
    public Map<String, Object> getMetadata() { return metadata; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private String name;
        private String instructions;
        private final List<Tool> tools = new ArrayList<Tool>();
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        public Builder tool(Tool tool) {
            tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        public Builder metadata(String name, Object value) {
            metadata.put(name, value);
            return this;
        }

        public Skill build() {
            return new Skill(this);
        }
    }
}
