package io.github.gitsilence.agent.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, Tool> tools;
    private final Collection<ToolDefinition> definitions;

    private DefaultToolRegistry(Builder builder) {
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<String, Tool>(builder.tools));
        Collection<ToolDefinition> collected = new ArrayList<ToolDefinition>();
        for (Tool tool : tools.values()) {
            collected.add(tool.definition());
        }
        this.definitions = Collections.unmodifiableCollection(collected);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Collection<ToolDefinition> definitions() {
        return definitions;
    }

    public static final class Builder {
        private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

        public Builder register(Tool tool) {
            Objects.requireNonNull(tool, "tool");
            String name = tool.definition().getName();
            if (tools.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate tool name: " + name);
            }
            tools.put(name, tool);
            return this;
        }

        public Builder registerAll(Collection<? extends Tool> tools) {
            for (Tool tool : tools) {
                register(tool);
            }
            return this;
        }

        public DefaultToolRegistry build() {
            return new DefaultToolRegistry(this);
        }
    }
}
