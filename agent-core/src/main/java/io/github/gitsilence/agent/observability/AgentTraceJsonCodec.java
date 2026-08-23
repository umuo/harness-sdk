package io.github.gitsilence.agent.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.model.Usage;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Stable JSON wire encoding shared by logging and platform exporters. */
public final class AgentTraceJsonCodec {

    public static final String SCHEMA_VERSION = "2";

    private final ObjectMapper mapper = new ObjectMapper();

    public String toJson(AgentTrace trace) {
        try {
            return mapper.writeValueAsString(toJsonNode(trace));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(
                "Cannot encode Agent trace as JSON: " + error.getMessage(),
                error
            );
        }
    }

    public byte[] toUtf8(AgentTrace trace) {
        return toJson(trace).getBytes(StandardCharsets.UTF_8);
    }

    public JsonNode toJsonNode(AgentTrace trace) {
        Objects.requireNonNull(trace, "trace");
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("traceId", trace.getTraceId());
        root.put("turnId", trace.getTurnId());
        root.put("parentTurnId", trace.getParentTurnId());
        root.put("parentSpanId", trace.getParentSpanId());
        root.put("agentName", trace.getAgentName());
        root.put("status", trace.getStatus().name());
        root.put("startedAt", trace.getStartedAt().toString());
        root.put("endedAt", trace.getEndedAt().toString());
        root.put("durationNanos", trace.getDurationNanos());
        root.put("stepCount", trace.getStepCount());
        root.put("modelCallCount", trace.getModelCallCount());
        root.put("toolCallCount", trace.getToolCallCount());
        root.put("toolErrorCount", trace.getToolErrorCount());
        root.put("modelStreamEventCount", trace.getModelStreamEventCount());
        root.set("usage", usage(trace.getUsage()));
        root.set("attributes", attributes(trace.getAttributes()));
        root.put("errorType", trace.getErrorType());
        root.put("errorMessage", trace.getErrorMessage());

        ArrayNode spans = root.putArray("spans");
        for (AgentSpan span : trace.getSpans()) {
            ObjectNode encoded = spans.addObject();
            encoded.put("traceId", span.getTraceId());
            encoded.put("spanId", span.getSpanId());
            encoded.put("parentSpanId", span.getParentSpanId());
            encoded.put("name", span.getName());
            encoded.put("kind", span.getKind().name());
            encoded.put("status", span.getStatus().name());
            encoded.put("startedAt", span.getStartedAt().toString());
            encoded.put("endedAt", span.getEndedAt().toString());
            encoded.put("durationNanos", span.getDurationNanos());
            encoded.set("input", attributes(span.getInput()));
            encoded.set("output", attributes(span.getOutput()));
            encoded.set("attributes", attributes(span.getAttributes()));
            encoded.put("errorType", span.getErrorType());
            encoded.put("errorMessage", span.getErrorMessage());
        }
        return root;
    }

    private ObjectNode usage(Usage usage) {
        ObjectNode encoded = mapper.createObjectNode();
        encoded.put("inputTokens", usage.getInputTokens());
        encoded.put("outputTokens", usage.getOutputTokens());
        encoded.put("totalTokens", usage.getTotalTokens());
        return encoded;
    }

    private ObjectNode attributes(Map<String, Object> values) {
        ObjectNode encoded = mapper.createObjectNode();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            encoded.set(entry.getKey(), value(entry.getValue()));
        }
        return encoded;
    }

    private JsonNode value(Object value) {
        try {
            return mapper.valueToTree(value);
        } catch (IllegalArgumentException error) {
            return mapper.getNodeFactory().textNode(String.valueOf(value));
        }
    }
}
