package io.github.gitsilence.agent.tool;

import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {

    Optional<Tool> find(String name);

    Collection<ToolDefinition> definitions();
}
