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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * OpenAI Responses API provider with non-streaming and SSE streaming support.
 */
public final class OpenAiResponsesChatModel extends AbstractHttpChatModel {

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String RESPONSES_PATH = "responses";

    private final String model;

    private OpenAiResponsesChatModel(Builder builder) {
        super(
            builder.transport,
            resolveEndpoint(builder.baseUrl, RESPONSES_PATH),
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
        return "OpenAI Responses API";
    }

    @Override
    protected ObjectNode encodeRequest(ModelRequest request, boolean stream) {
        Objects.requireNonNull(request, "request");
        ObjectNode root = mapper().createObjectNode();
        root.put("model", model);
        root.put("stream", stream);

        String instructions = instructions(request.getMessages());
        if (instructions != null) {
            root.put("instructions", instructions);
        }
        ArrayNode input = root.putArray("input");
        for (ChatMessage message : request.getMessages()) {
            if (message.getRole() != MessageRole.SYSTEM) {
                addInputItems(input, message);
            }
        }
        if (!request.getTools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition definition : request.getTools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                tool.put("name", definition.getName());
                tool.put("description", definition.getDescription());
                tool.set("parameters", schema(definition));
            }
        }
        if (request.getOptions().getTemperature() != null) {
            root.put("temperature", request.getOptions().getTemperature());
        }
        if (request.getOptions().getMaxTokens() != null) {
            root.put("max_output_tokens", request.getOptions().getMaxTokens());
        }
        addExtensions(root, request);
        return root;
    }

    private void addInputItems(ArrayNode input, ChatMessage message) {
        if (message.getRole() == MessageRole.TOOL) {
            ObjectNode result = input.addObject();
            result.put("type", "function_call_output");
            result.put("call_id", message.getToolCallId());
            result.put("output", message.getContent());
            return;
        }

        if (message.getContent() != null) {
            ObjectNode item = input.addObject();
            item.put("type", "message");
            item.put("role", message.getRole() == MessageRole.ASSISTANT
                ? "assistant" : "user");
            ArrayNode content = item.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", message.getRole() == MessageRole.ASSISTANT
                ? "output_text" : "input_text");
            text.put("text", message.getContent());
        }
        if (message.getRole() == MessageRole.ASSISTANT) {
            for (ToolCall call : message.getToolCalls()) {
                ObjectNode item = input.addObject();
                item.put("type", "function_call");
                item.put("call_id", call.getId());
                item.put("name", call.getName());
                item.put("arguments", call.getArguments());
            }
        }
    }

    @Override
    protected ModelResponse decodeResponse(JsonNode root) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<ToolCall>();
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                String type = item.path("type").asText();
                if ("message".equals(type)) {
                    appendOutputText(text, item.path("content"));
                } else if ("function_call".equals(type)) {
                    calls.add(decodeFunctionCall(item));
                }
            }
        }
        String content = text.length() == 0 ? null : text.toString();
        return new ModelResponse(
            ChatMessage.assistant(content, calls),
            decodeUsage(root.path("usage")),
            metadata(root)
        );
    }

    @Override
    protected ModelStreamDecoder newStreamDecoder(ModelStreamListener listener) {
        return new ResponsesStreamDecoder(listener);
    }

    private final class ResponsesStreamDecoder implements ModelStreamDecoder {
        private final ModelStreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, ToolAccumulator> tools =
            new TreeMap<Integer, ToolAccumulator>();
        private final Map<String, Object> metadata =
            new LinkedHashMap<String, Object>();
        private ModelResponse completedResponse;
        private Usage usage;
        private boolean started;

        private ResponsesStreamDecoder(ModelStreamListener listener) {
            this.listener = listener;
        }

        @Override
        public void onEvent(SseEvent event) throws Exception {
            if (event.getData().trim().isEmpty() || "[DONE]".equals(event.getData().trim())) {
                return;
            }
            JsonNode payload = mapper().readTree(event.getData());
            String type = eventType(event, payload);
            if ("response.created".equals(type)) {
                JsonNode response = payload.path("response");
                captureMetadata(response);
                startIfNeeded();
            } else if ("response.output_text.delta".equals(type)) {
                startIfNeeded();
                String delta = payload.path("delta").asText("");
                text.append(delta);
                listener.onEvent(ModelStreamEvent.textDelta(delta));
            } else if ("response.output_item.added".equals(type)) {
                consumeOutputItem(payload.path("output_index").asInt(0), payload.path("item"));
            } else if ("response.output_item.done".equals(type)) {
                consumeDoneItem(payload.path("output_index").asInt(0), payload.path("item"));
            } else if ("response.function_call_arguments.delta".equals(type)) {
                consumeArgumentsDelta(
                    payload.path("output_index").asInt(0),
                    payload.path("delta").asText("")
                );
            } else if ("response.function_call_arguments.done".equals(type)) {
                int index = payload.path("output_index").asInt(0);
                ToolAccumulator tool = tool(index);
                if (payload.hasNonNull("arguments")) {
                    tool.arguments.setLength(0);
                    tool.arguments.append(payload.get("arguments").asText());
                }
            } else if ("response.completed".equals(type)) {
                JsonNode response = payload.path("response");
                completedResponse = decodeResponse(response);
                usage = completedResponse.getUsage();
                captureMetadata(response);
                if (usage != null) {
                    listener.onEvent(ModelStreamEvent.usage(usage));
                }
            } else if ("response.failed".equals(type) || "error".equals(type)) {
                throw new ModelException("OpenAI Responses stream failed: " + errorMessage(payload));
            }
        }

        private void consumeOutputItem(int index, JsonNode item) {
            if (!"function_call".equals(item.path("type").asText())) {
                return;
            }
            startIfNeeded();
            ToolAccumulator tool = tool(index);
            if (item.hasNonNull("call_id")) {
                tool.id = item.get("call_id").asText();
            }
            if (item.hasNonNull("name")) {
                tool.name = item.get("name").asText();
            }
            if (!tool.announced) {
                tool.announced = true;
                listener.onEvent(ModelStreamEvent.toolCallStarted(
                    index, tool.id, tool.name
                ));
            }
        }

        private void consumeDoneItem(int index, JsonNode item) {
            if (!"function_call".equals(item.path("type").asText())) {
                return;
            }
            consumeOutputItem(index, item);
            ToolAccumulator tool = tool(index);
            if (item.hasNonNull("arguments")) {
                tool.arguments.setLength(0);
                tool.arguments.append(item.get("arguments").asText());
            }
        }

        private void consumeArgumentsDelta(int index, String delta) {
            startIfNeeded();
            ToolAccumulator tool = tool(index);
            tool.arguments.append(delta);
            listener.onEvent(ModelStreamEvent.toolArgumentsDelta(
                index, tool.id, tool.name, delta
            ));
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
            if (completedResponse != null) {
                return completedResponse;
            }
            List<ToolCall> calls = new ArrayList<ToolCall>();
            for (ToolAccumulator tool : tools.values()) {
                calls.add(new ToolCall(tool.id, tool.name, tool.arguments.toString()));
            }
            return new ModelResponse(
                ChatMessage.assistant(text.length() == 0 ? null : text.toString(), calls),
                usage,
                metadata
            );
        }

        private void captureMetadata(JsonNode response) {
            metadata.putAll(metadata(response));
        }
    }

    private static String instructions(List<ChatMessage> messages) {
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

    private void appendOutputText(StringBuilder text, JsonNode content) {
        if (!content.isArray()) {
            return;
        }
        for (JsonNode block : content) {
            if ("output_text".equals(block.path("type").asText())
                && block.hasNonNull("text")) {
                text.append(block.get("text").asText());
            }
        }
    }

    private static ToolCall decodeFunctionCall(JsonNode item) {
        return new ToolCall(
            requiredText(item, "call_id"),
            requiredText(item, "name"),
            item.path("arguments").asText("{}")
        );
    }

    private static Usage decodeUsage(JsonNode usage) {
        if (!usage.isObject()) {
            return null;
        }
        long input = usage.path("input_tokens").asLong(0L);
        long output = usage.path("output_tokens").asLong(0L);
        return new Usage(input, output, usage.path("total_tokens").asLong(input + output));
    }

    private static Map<String, Object> metadata(JsonNode response) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (response.hasNonNull("id")) {
            metadata.put("responseId", response.get("id").asText());
        }
        if (response.hasNonNull("model")) {
            metadata.put("model", response.get("model").asText());
        }
        if (response.hasNonNull("status")) {
            metadata.put("status", response.get("status").asText());
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
        if (error.hasNonNull("message")) {
            return error.get("message").asText();
        }
        JsonNode responseError = payload.path("response").path("error");
        if (responseError.hasNonNull("message")) {
            return responseError.get("message").asText();
        }
        return payload.toString();
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

    private static boolean isReserved(String name) {
        return "model".equals(name)
            || "input".equals(name)
            || "instructions".equals(name)
            || "tools".equals(name)
            || "temperature".equals(name)
            || "max_output_tokens".equals(name)
            || "stream".equals(name);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new ModelException("Missing response field: " + field);
        }
        return value.asText();
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
        private String id;
        private String name;
        private boolean announced;
        private final StringBuilder arguments = new StringBuilder();
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private String model;
        private int connectTimeoutMillis = 10_000;
        private int readTimeoutMillis = 60_000;
        private HttpTransport transport = JdkHttpTransport.shared();
        private final Map<String, String> headers =
            new LinkedHashMap<String, String>();

        /**
         * Sets the API base URL through the version prefix. The provider
         * appends {@code /responses} internally.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = requireText(baseUrl, "baseUrl");
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

        public OpenAiResponsesChatModel build() {
            return new OpenAiResponsesChatModel(this);
        }
    }
}
