package io.github.gitsilence.agent.tool;

/**
 * Final model-context boundary applied to every successful or error result.
 * A policy may also preserve omitted content outside the model context.
 */
@FunctionalInterface
public interface ToolResultPolicy {

    ToolResult apply(ToolResult result);

    static ToolResultPolicy identity() {
        return result -> result;
    }
}
