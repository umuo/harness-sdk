package io.github.gitsilence.agent.agent;

import io.github.gitsilence.agent.model.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentRequest {

    private final String input;
    private final List<ChatMessage> initialMessages;
    private final Map<String, Object> metadata;
    private final Map<String, Object> variables;

    private AgentRequest(Builder builder) {
        this.input = requireText(builder.input, "input");
        this.initialMessages = Collections.unmodifiableList(
            new ArrayList<ChatMessage>(builder.initialMessages)
        );
        this.metadata = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(builder.metadata)
        );
        this.variables = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(builder.variables)
        );
    }

    public static AgentRequest of(String input) {
        return builder().input(input).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getInput() { return input; }
    public List<ChatMessage> getInitialMessages() { return initialMessages; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Map<String, Object> getVariables() { return variables; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private String input;
        private final List<ChatMessage> initialMessages = new ArrayList<ChatMessage>();
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        private final Map<String, Object> variables = new LinkedHashMap<String, Object>();

        public Builder input(String input) {
            this.input = input;
            return this;
        }

        public Builder initialMessage(ChatMessage message) {
            this.initialMessages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder initialMessages(List<ChatMessage> messages) {
            this.initialMessages.addAll(messages);
            return this;
        }

        public Builder metadata(String name, Object value) {
            this.metadata.put(name, value);
            return this;
        }

        public Builder variable(String name, Object value) {
            this.variables.put(name, value);
            return this;
        }

        public AgentRequest build() {
            return new AgentRequest(this);
        }
    }
}
