package io.github.gitsilence.agent.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ChatMessage {

    private final MessageRole role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String toolName;
    private final boolean error;

    private ChatMessage(MessageRole role,
                        String content,
                        List<ToolCall> toolCalls,
                        String toolCallId,
                        String toolName,
                        boolean error) {
        this.role = Objects.requireNonNull(role, "role");
        this.content = content;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.error = error;
    }

    public static ChatMessage system(String content) {
        return plain(MessageRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return plain(MessageRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return assistant(content, Collections.<ToolCall>emptyList());
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        Objects.requireNonNull(toolCalls, "toolCalls");
        return new ChatMessage(
            MessageRole.ASSISTANT,
            content,
            toolCalls,
            null,
            null,
            false
        );
    }

    public static ChatMessage tool(String toolCallId,
                                   String toolName,
                                   String content,
                                   boolean error) {
        return new ChatMessage(
            MessageRole.TOOL,
            Objects.requireNonNull(content, "content"),
            Collections.<ToolCall>emptyList(),
            requireText(toolCallId, "toolCallId"),
            requireText(toolName, "toolName"),
            error
        );
    }

    private static ChatMessage plain(MessageRole role, String content) {
        return new ChatMessage(
            role,
            requireText(content, "content"),
            Collections.<ToolCall>emptyList(),
            null,
            null,
            false
        );
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isError() {
        return error;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
