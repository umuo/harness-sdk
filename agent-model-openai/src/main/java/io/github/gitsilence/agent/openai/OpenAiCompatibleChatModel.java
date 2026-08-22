package io.github.gitsilence.agent.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.http.AbstractHttpChatModel;
import io.github.gitsilence.agent.http.HttpTransport;
import io.github.gitsilence.agent.http.JdkHttpTransport;
import io.github.gitsilence.agent.http.ModelStreamDecoder;
import io.github.gitsilence.agent.http.SseEvent;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.MessageRole;
import io.github.gitsilence.agent.model.ModelException;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.Usage;
import io.github.gitsilence.agent.model.stream.ModelStreamEvent;
import io.github.gitsilence.agent.model.stream.ModelStreamListener;
import io.github.gitsilence.agent.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Provider for OpenAI-compatible Chat Completions endpoints.
 */
public final class OpenAiCompatibleChatModel extends AbstractHttpChatModel {

    public static final String DEFAULT_ENDPOINT =
        "https://api.openai.com/v1/chat/completions";

    private final String model;

    private OpenAiCompatibleChatModel(Builder builder) {
        super(
            builder.transport,
            builder.endpoint,
            headers(builder),
            builder.connectTimeoutMillis,
            builder.readTimeoutMillis
        );
        this.model = requireText(builder.model, "model");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected String providerName() {
        return "OpenAI-compatible Chat Completions";
    }

    @Override
    protected ObjectNode encodeRequest(ModelRequest request, boolean stream) {
        Objects.requireNonNull(request, "request");
        ObjectNode root = mapper().createObjectNode();
        root.put("model", model);
        root.put("stream", stream);
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : request.getMessages()) {
            messages.add(messageToJson(message));
        }
        if (!request.getTools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition definition : request.getTools()) {
                ObjectNode entry = tools.addObject();
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", definition.getName());
                function.put("description", definition.getDescription());
                function.set("parameters", schema(definition));
            }
        }
        if (request.getOptions().getTemperature() != null) {
            root.put("temperature", request.getOptions().getTemperature());
        }
        if (request.getOptions().getMaxTokens() != null) {
            root.put("max_tokens", request.getOptions().getMaxTokens());
        }
        addExtensions(root, request);
        return root;
    }

    private ObjectNode messageToJson(ChatMessage message) {
        ObjectNode node = mapper().createObjectNode();
        node.put("role", role(message.getRole()));
        if (message.getRole() == MessageRole.TOOL) {
            node.put("tool_call_id", message.getToolCallId());
            node.put("content", message.getContent());
            return node;
        }
        if (message.getContent() == null) {
            node.putNull("content");
        } else {
            node.put("content", message.getContent());
        }
        if (message.getRole() == MessageRole.ASSISTANT
            && !message.getToolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.getToolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", call.getId());
                callNode.put("type", "function");
                ObjectNode function = callNode.putObject("function");
                function.put("name", call.getName());
                function.put("arguments", call.getArguments());
            }
        }
        return node;
    }

    @Override
    protected ModelResponse decodeResponse(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new ModelException("Model response contains no choices");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw new ModelException("Model response choice contains no message");
        }

        String content = nullableText(message.get("content"));
        List<ToolCall> calls = decodeToolCalls(message.path("tool_calls"));
        Usage usage = decodeUsage(root.path("usage"));
        Map<String, Object> metadata = responseMetadata(root, choice);
        return new ModelResponse(ChatMessage.assistant(content, calls), usage, metadata);
    }

    @Override
    protected ModelStreamDecoder newStreamDecoder(ModelStreamListener listener) {
        return new ChatCompletionsStreamDecoder(listener);
    }

    private final class ChatCompletionsStreamDecoder implements ModelStreamDecoder {
        private final ModelStreamListener listener;
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolAccumulator> tools =
            new TreeMap<Integer, ToolAccumulator>();
        private final Map<String, Object> metadata =
            new LinkedHashMap<String, Object>();
        private Usage usage;
        private boolean started;

        private ChatCompletionsStreamDecoder(ModelStreamListener listener) {
            this.listener = listener;
        }

        @Override
        public void onEvent(SseEvent event) throws Exception {
            String data = event.getData();
            if (data.trim().isEmpty() || "[DONE]".equals(data.trim())) {
                return;
            }
            JsonNode root = mapper().readTree(data);
            captureMetadata(root);
            if (!started) {
                started = true;
                listener.onEvent(ModelStreamEvent.responseStarted(metadata));
            }
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode choice = choices.get(0);
                if (choice.hasNonNull("finish_reason")) {
                    metadata.put("finishReason", choice.get("finish_reason").asText());
                }
                JsonNode delta = choice.path("delta");
                if (delta.hasNonNull("content")) {
                    String text = delta.get("content").asText();
                    content.append(text);
                    listener.onEvent(ModelStreamEvent.textDelta(text));
                }
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        consumeToolDelta(call);
                    }
                }
            }
            Usage current = decodeUsage(root.path("usage"));
            if (current != null) {
                usage = current;
                listener.onEvent(ModelStreamEvent.usage(current));
            }
        }

        private void consumeToolDelta(JsonNode call) {
            int index = call.path("index").asInt(0);
            ToolAccumulator tool = tools.get(index);
            boolean newTool = tool == null;
            if (newTool) {
                tool = new ToolAccumulator(index);
                tools.put(index, tool);
            }
            if (call.hasNonNull("id")) {
                tool.id = call.get("id").asText();
            }
            JsonNode function = call.path("function");
            if (function.hasNonNull("name")) {
                tool.name = function.get("name").asText();
            }
            if (newTool) {
                listener.onEvent(ModelStreamEvent.toolCallStarted(
                    index, tool.id, tool.name
                ));
            }
            if (function.hasNonNull("arguments")) {
                String arguments = function.get("arguments").asText();
                tool.arguments.append(arguments);
                listener.onEvent(ModelStreamEvent.toolArgumentsDelta(
                    index, tool.id, tool.name, arguments
                ));
            }
        }

        @Override
        public ModelResponse finish() {
            List<ToolCall> calls = new ArrayList<ToolCall>();
            for (ToolAccumulator tool : tools.values()) {
                calls.add(new ToolCall(tool.id, tool.name, tool.arguments.toString()));
            }
            String text = content.length() == 0 ? null : content.toString();
            return new ModelResponse(
                ChatMessage.assistant(text, calls), usage, metadata
            );
        }

        private void captureMetadata(JsonNode root) {
            if (root.hasNonNull("id")) {
                metadata.put("responseId", root.get("id").asText());
            }
            if (root.hasNonNull("model")) {
                metadata.put("model", root.get("model").asText());
            }
        }
    }

    private JsonNode schema(ToolDefinition definition) {
        try {
            return mapper().readTree(definition.getInputSchema());
        } catch (Exception e) {
            throw new ModelException("Invalid schema for tool " + definition.getName(), e);
        }
    }

    private void addExtensions(ObjectNode root, ModelRequest request) {
        for (Map.Entry<String, Object> extension
                : request.getOptions().getExtensions().entrySet()) {
            if (isReserved(extension.getKey())) {
                throw new ModelException(
                    "Model option extension cannot replace reserved field: "
                        + extension.getKey()
                );
            }
            root.set(extension.getKey(), mapper().valueToTree(extension.getValue()));
        }
    }

    private static List<ToolCall> decodeToolCalls(JsonNode toolCalls) {
        if (!toolCalls.isArray()) {
            return Collections.emptyList();
        }
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (JsonNode call : toolCalls) {
            JsonNode function = call.path("function");
            JsonNode arguments = function.get("arguments");
            calls.add(new ToolCall(
                requiredText(call, "id"),
                requiredText(function, "name"),
                arguments == null
                    ? "{}"
                    : arguments.isTextual() ? arguments.asText() : arguments.toString()
            ));
        }
        return calls;
    }

    private static Usage decodeUsage(JsonNode usage) {
        if (!usage.isObject()) {
            return null;
        }
        return new Usage(
            usage.path("prompt_tokens").asLong(0L),
            usage.path("completion_tokens").asLong(0L),
            usage.path("total_tokens").asLong(0L)
        );
    }

    private static Map<String, Object> responseMetadata(JsonNode root,
                                                         JsonNode choice) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (root.hasNonNull("id")) {
            metadata.put("responseId", root.get("id").asText());
        }
        if (root.hasNonNull("model")) {
            metadata.put("model", root.get("model").asText());
        }
        if (choice.hasNonNull("finish_reason")) {
            metadata.put("finishReason", choice.get("finish_reason").asText());
        }
        return metadata;
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String role(MessageRole role) {
        switch (role) {
            case SYSTEM: return "system";
            case USER: return "user";
            case ASSISTANT: return "assistant";
            case TOOL: return "tool";
            default: throw new IllegalArgumentException("Unsupported role: " + role);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new ModelException("Missing response field: " + field);
        }
        return value.asText();
    }

    private static boolean isReserved(String name) {
        return "model".equals(name)
            || "messages".equals(name)
            || "tools".equals(name)
            || "temperature".equals(name)
            || "max_tokens".equals(name)
            || "stream".equals(name);
    }

    private static Map<String, String> headers(Builder builder) {
        Map<String, String> headers =
            new LinkedHashMap<String, String>(builder.headers);
        if (builder.apiKey != null && !builder.apiKey.trim().isEmpty()) {
            headers.put("Authorization", "Bearer " + builder.apiKey);
        }
        return headers;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static final class ToolAccumulator {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolAccumulator(int index) {
            this.index = index;
        }
    }

    public static final class Builder {
        private String endpoint = DEFAULT_ENDPOINT;
        private String apiKey;
        private String model;
        private int connectTimeoutMillis = 10_000;
        private int readTimeoutMillis = 60_000;
        private HttpTransport transport = JdkHttpTransport.shared();
        private final Map<String, String> headers =
            new LinkedHashMap<String, String>();

        public Builder endpoint(String endpoint) {
            this.endpoint = requireText(endpoint, "endpoint");
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder connectTimeoutMillis(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("connectTimeoutMillis must be positive");
            }
            this.connectTimeoutMillis = value;
            return this;
        }

        public Builder readTimeoutMillis(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("readTimeoutMillis must be positive");
            }
            this.readTimeoutMillis = value;
            return this;
        }

        public Builder transport(HttpTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(requireText(name, "header name"),
                Objects.requireNonNull(value, "header value"));
            return this;
        }

        public OpenAiCompatibleChatModel build() {
            return new OpenAiCompatibleChatModel(this);
        }
    }
}
