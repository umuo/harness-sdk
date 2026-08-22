package io.github.gitsilence.agent.model;

import io.github.gitsilence.agent.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ModelRequest {

    private final List<ChatMessage> messages;
    private final List<ToolDefinition> tools;
    private final ModelOptions options;

    public ModelRequest(List<ChatMessage> messages,
                        List<ToolDefinition> tools,
                        ModelOptions options) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
        this.tools = Collections.unmodifiableList(new ArrayList<ToolDefinition>(tools));
        this.options = options == null ? ModelOptions.empty() : options;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }

    public ModelOptions getOptions() {
        return options;
    }
}
