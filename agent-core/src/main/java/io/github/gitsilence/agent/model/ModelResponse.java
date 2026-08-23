package io.github.gitsilence.agent.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ModelResponse {

    private final ChatMessage assistantMessage;
    private final Usage usage;
    private final Map<String, Object> metadata;
    private final ModelExchange exchange;

    public ModelResponse(ChatMessage assistantMessage,
                         Usage usage,
                         Map<String, Object> metadata) {
        this(assistantMessage, usage, metadata, null);
    }

    public ModelResponse(ChatMessage assistantMessage,
                         Usage usage,
                         Map<String, Object> metadata,
                         ModelExchange exchange) {
        this.assistantMessage = Objects.requireNonNull(assistantMessage, "assistantMessage");
        if (assistantMessage.getRole() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("Model response must contain an assistant message");
        }
        this.usage = usage;
        this.metadata = metadata == null
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
        this.exchange = exchange;
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

    /** Provider HTTP exchange, or {@code null} for non-HTTP/custom Models. */
    public ModelExchange getExchange() {
        return exchange;
    }

    public ModelResponse withExchange(ModelExchange value) {
        return new ModelResponse(assistantMessage, usage, metadata,
            Objects.requireNonNull(value, "exchange"));
    }
}
