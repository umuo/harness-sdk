package io.github.gitsilence.agent.tool;

public class ToolExecutionException extends RuntimeException {

    private final String toolName;

    public ToolExecutionException(String toolName, Throwable cause) {
        super("Tool '" + toolName + "' failed: " + cause.getMessage(), cause);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
