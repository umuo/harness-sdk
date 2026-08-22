package io.github.gitsilence.agent.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ModelResponse {

    private final ChatMessage assistantMessage;
    private final Usage usage;
    private final Map<String, Object> metadata;

    public ModelResponse(ChatMessage assistantMessage,
                         Usage usage,
                         Map<String, Object> metadata) {
        this.assistantMessage = Objects.requireNonNull(assistantMessage, "assistantMessage");
        if (assistantMessage.getRole() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("Model response must contain an assistant message");
        }
        this.usage = usage;
        this.metadata = metadata == null
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    public static ModelResponse of(ChatMessage assistantMessage) {
        return new ModelResponse(assistantMessage, null, null);
    }

    public ChatMessage getAssistantMessage() {
        return assistantMessage;
    }

    public Usage getUsage() {
        return usage;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
