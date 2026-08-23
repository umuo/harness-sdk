package io.github.gitsilence.agent.anthropic;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Anthropic Messages API provider with tool use and SSE streaming support.
 */
public final class AnthropicChatModel extends AbstractHttpChatModel {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String MESSAGES_PATH = "v1/messages";
    public static final String DEFAULT_API_VERSION = "2023-06-01";

    private final String model;
    private final int defaultMaxTokens;

    private AnthropicChatModel(Builder builder) {
        super(
            builder.transport,
            resolveEndpoint(builder.baseUrl, MESSAGES_PATH),
            headers(builder),
            builder.connectTimeoutMillis,
            builder.readTimeoutMillis
        );
        this.model = requireText(builder.model, "model");
        this.defaultMaxTokens = builder.defaultMaxTokens;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected String providerName() {
        return "Anthropic Messages API";
    }

    @Override
    protected ObjectNode encodeRequest(ModelRequest request, boolean stream) {
        Objects.requireNonNull(request, "request");
        ObjectNode root = mapper().createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.getOptions().getMaxTokens() == null
            ? defaultMaxTokens : request.getOptions().getMaxTokens());
        root.put("stream", stream);

        String system = systemInstructions(request.getMessages());
        if (system != null) {
            root.put("system", system);
        }
        ArrayNode messages = root.putArray("messages");
        encodeMessages(messages, request.getMessages());

        if (!request.getTools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition definition : request.getTools()) {
                ObjectNode tool = tools.addObject();
                tool.put("name", definition.getName());
                tool.put("description", definition.getDescription());
                tool.set("input_schema", schema(definition));
            }
        }
        if (request.getOptions().getTemperature() != null) {
            root.put("temperature", request.getOptions().getTemperature());
        }
        addExtensions(root, request);
        return root;
    }

    private void encodeMessages(ArrayNode output, List<ChatMessage> messages) {
        int index = 0;
        while (index < messages.size()) {
            ChatMessage message = messages.get(index);
            if (message.getRole() == MessageRole.SYSTEM) {
                index++;
                continue;
            }
            if (message.getRole() == MessageRole.TOOL) {
                ObjectNode user = output.addObject();
                user.put("role", "user");
                ArrayNode content = user.putArray("content");
                do {
                    addToolResult(content, messages.get(index));
                    index++;
                } while (index < messages.size()
                    && messages.get(index).getRole() == MessageRole.TOOL);
                continue;
            }
            ObjectNode encoded = output.addObject();
            encoded.put("role", message.getRole() == MessageRole.ASSISTANT
                ? "assistant" : "user");
            ArrayNode content = encoded.putArray("content");
            if (message.getContent() != null) {
                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text", message.getContent());
            }
            if (message.getRole() == MessageRole.ASSISTANT) {
                for (ToolCall call : message.getToolCalls()) {
                    ObjectNode toolUse = content.addObject();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", call.getId());
                    toolUse.put("name", call.getName());
                    toolUse.set("input", arguments(call));
                }
            }
            index++;
        }
    }

    private void addToolResult(ArrayNode content, ChatMessage message) {
        ObjectNode result = content.addObject();
        result.put("type", "tool_result");
        result.put("tool_use_id", message.getToolCallId());
        result.put("content", message.getContent());
        if (message.isError()) {
            result.put("is_error", true);
        }
    }

    @Override
    protected ModelResponse decodeResponse(JsonNode root) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<ToolCall>();
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type) && block.hasNonNull("text")) {
                    text.append(block.get("text").asText());
                } else if ("tool_use".equals(type)) {
                    calls.add(new ToolCall(
                        requiredText(block, "id"),
                        requiredText(block, "name"),
                        block.has("input") ? block.get("input").toString() : "{}"
                    ));
                }
            }
        }
        return new ModelResponse(
            ChatMessage.assistant(text.length() == 0 ? null : text.toString(), calls),
            decodeUsage(root.path("usage")),
            metadata(root)
        );
    }

    @Override
    protected ModelStreamDecoder newStreamDecoder(ModelStreamListener listener) {
        return new AnthropicStreamDecoder(listener);
    }

    private final class AnthropicStreamDecoder implements ModelStreamDecoder {
        private final ModelStreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, ToolAccumulator> tools =
            new TreeMap<Integer, ToolAccumulator>();
        private final Map<String, Object> metadata =
            new LinkedHashMap<String, Object>();
        private long inputTokens;
        private long outputTokens;
        private boolean started;

        private AnthropicStreamDecoder(ModelStreamListener listener) {
            this.listener = listener;
        }

        @Override
        public void onEvent(SseEvent event) throws Exception {
            if (event.getData().trim().isEmpty()) {
                return;
            }
            JsonNode payload = mapper().readTree(event.getData());
            String type = eventType(event, payload);
            if ("message_start".equals(type)) {
                JsonNode message = payload.path("message");
                metadata.putAll(metadata(message));
                JsonNode usage = message.path("usage");
                inputTokens = usage.path("input_tokens").asLong(0L);
                outputTokens = usage.path("output_tokens").asLong(0L);
                startIfNeeded();
            } else if ("content_block_start".equals(type)) {
                consumeBlockStart(payload.path("index").asInt(0),
                    payload.path("content_block"));
            } else if ("content_block_delta".equals(type)) {
                consumeDelta(payload.path("index").asInt(0), payload.path("delta"));
            } else if ("message_delta".equals(type)) {
                JsonNode delta = payload.path("delta");
                if (delta.hasNonNull("stop_reason")) {
                    metadata.put("stopReason", delta.get("stop_reason").asText());
                }
                JsonNode usage = payload.path("usage");
                if (usage.has("output_tokens")) {
                    outputTokens = usage.get("output_tokens").asLong();
                    listener.onEvent(ModelStreamEvent.usage(currentUsage()));
                }
            } else if ("error".equals(type)) {
                throw new ModelException("Anthropic stream failed: " + errorMessage(payload));
            }
        }

        private void consumeBlockStart(int index, JsonNode block) {
            startIfNeeded();
            String type = block.path("type").asText();
            if ("text".equals(type) && block.hasNonNull("text")) {
                String initial = block.get("text").asText();
                if (!initial.isEmpty()) {
                    text.append(initial);
                    listener.onEvent(ModelStreamEvent.textDelta(initial));
                }
            } else if ("tool_use".equals(type)) {
                ToolAccumulator tool = tool(index);
                tool.id = block.path("id").asText(null);
                tool.name = block.path("name").asText(null);
                listener.onEvent(ModelStreamEvent.toolCallStarted(
                    index, tool.id, tool.name
                ));
            }
        }

        private void consumeDelta(int index, JsonNode delta) {
            startIfNeeded();
            String type = delta.path("type").asText();
            if ("text_delta".equals(type)) {
                String value = delta.path("text").asText("");
                text.append(value);
                listener.onEvent(ModelStreamEvent.textDelta(value));
            } else if ("input_json_delta".equals(type)) {
                String value = delta.path("partial_json").asText("");
                ToolAccumulator tool = tool(index);
                tool.arguments.append(value);
                listener.onEvent(ModelStreamEvent.toolArgumentsDelta(
                    index, tool.id, tool.name, value
                ));
            }
        }

        private ToolAccumulator tool(int index) {
            ToolAccumulator tool = tools.get(index);
            if (tool == null) {
                tool = new ToolAccumulator();
                tools.put(index, tool);
            }
            return tool;
        }

        private void startIfNeeded() {
            if (!started) {
                started = true;
                listener.onEvent(ModelStreamEvent.responseStarted(metadata));
            }
        }

        @Override
        public ModelResponse finish() {
            List<ToolCall> calls = new ArrayList<ToolCall>();
            for (ToolAccumulator tool : tools.values()) {
                calls.add(new ToolCall(tool.id, tool.name, tool.arguments.toString()));
            }
            return new ModelResponse(
                ChatMessage.assistant(text.length() == 0 ? null : text.toString(), calls),
                currentUsage(),
                metadata
            );
        }

        private Usage currentUsage() {
            return new Usage(inputTokens, outputTokens, inputTokens + outputTokens);
        }
    }

    private static String systemInstructions(List<ChatMessage> messages) {
        StringBuilder value = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getRole() == MessageRole.SYSTEM) {
                if (value.length() > 0) {
                    value.append('\n');
                }
                value.append(message.getContent());
            }
        }
        return value.length() == 0 ? null : value.toString();
    }

    private JsonNode arguments(ToolCall call) {
        try {
            return mapper().readTree(call.getArguments());
        } catch (Exception e) {
            throw new ModelException("Invalid arguments for tool call " + call.getId(), e);
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

    private static Usage decodeUsage(JsonNode usage) {
        if (!usage.isObject()) {
            return null;
        }
        long input = usage.path("input_tokens").asLong(0L);
        long output = usage.path("output_tokens").asLong(0L);
        return new Usage(input, output, input + output);
    }

    private static Map<String, Object> metadata(JsonNode root) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (root.hasNonNull("id")) {
            metadata.put("responseId", root.get("id").asText());
        }
        if (root.hasNonNull("model")) {
            metadata.put("model", root.get("model").asText());
        }
        if (root.hasNonNull("stop_reason")) {
            metadata.put("stopReason", root.get("stop_reason").asText());
        }
        return metadata;
    }

    private static String eventType(SseEvent event, JsonNode payload) {
        return "message".equals(event.getEvent())
            ? payload.path("type").asText()
            : event.getEvent();
    }

    private static String errorMessage(JsonNode payload) {
        JsonNode error = payload.path("error");
        return error.hasNonNull("message")
            ? error.get("message").asText() : payload.toString();
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
            || "system".equals(name)
            || "tools".equals(name)
            || "temperature".equals(name)
            || "max_tokens".equals(name)
            || "stream".equals(name);
    }

    private static Map<String, String> headers(Builder builder) {
        Map<String, String> headers =
            new LinkedHashMap<String, String>(builder.headers);
        if (builder.apiKey != null && !builder.apiKey.trim().isEmpty()) {
            headers.put("x-api-key", builder.apiKey);
        }
        headers.put("anthropic-version", builder.apiVersion);
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
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private String apiVersion = DEFAULT_API_VERSION;
        private String model;
        private int defaultMaxTokens = 4096;
        private int connectTimeoutMillis = 10_000;
        private int readTimeoutMillis = 60_000;
        private HttpTransport transport = JdkHttpTransport.shared();
        private final Map<String, String> headers =
            new LinkedHashMap<String, String>();

        /**
         * Sets the Anthropic API host or proxy prefix. The provider appends
         * {@code /v1/messages} internally.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = requireText(baseUrl, "baseUrl");
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder apiVersion(String apiVersion) {
            this.apiVersion = requireText(apiVersion, "apiVersion");
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder defaultMaxTokens(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("defaultMaxTokens must be positive");
            }
            this.defaultMaxTokens = value;
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

        public AnthropicChatModel build() {
            return new AnthropicChatModel(this);
        }
    }
}
