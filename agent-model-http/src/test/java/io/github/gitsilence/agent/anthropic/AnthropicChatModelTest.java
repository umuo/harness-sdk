package io.github.gitsilence.agent.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gitsilence.agent.http.ModelStreamDecoder;
import io.github.gitsilence.agent.http.SseEvent;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.stream.ModelStreamEvent;
import io.github.gitsilence.agent.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicChatModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsSystemToolUseAndGroupedToolResults() {
        AnthropicChatModel model = AnthropicChatModel.builder()
            .model("test-model")
            .defaultMaxTokens(512)
            .build();
        ToolDefinition definition = ToolDefinition.builder()
            .name("echo")
            .description("Echoes text")
            .inputSchema("{\"type\":\"object\"}")
            .build();
        ModelRequest request = new ModelRequest(
            Arrays.asList(
                ChatMessage.system("Be concise."),
                ChatMessage.user("run tools"),
                ChatMessage.assistant(null, Arrays.asList(
                    new ToolCall("call-1", "echo", "{\"text\":\"a\"}"),
                    new ToolCall("call-2", "echo", "{\"text\":\"b\"}")
                )),
                ChatMessage.tool("call-1", "echo", "a", false),
                ChatMessage.tool("call-2", "echo", "failed", true)
            ),
            Collections.singletonList(definition),
            ModelOptions.empty()
        );

        JsonNode json = model.encodeRequest(request, false);

        assertEquals("Be concise.", json.path("system").asText());
        assertEquals(512, json.path("max_tokens").asInt());
        assertEquals("tool_use", json.path("messages").get(1)
            .path("content").get(0).path("type").asText());
        assertEquals(2, json.path("messages").get(2).path("content").size());
        assertTrue(json.path("messages").get(2).path("content").get(1)
            .path("is_error").asBoolean());
        assertTrue(json.path("tools").get(0).has("input_schema"));
    }

    @Test
    void decodesAnthropicToolUseResponse() throws Exception {
        AnthropicChatModel model = AnthropicChatModel.builder()
            .model("test-model")
            .build();
        JsonNode json = mapper.readTree("{"
            + "\"id\":\"msg-1\",\"model\":\"test-model\",\"stop_reason\":\"tool_use\","
            + "\"content\":[{\"type\":\"tool_use\",\"id\":\"call-1\","
            + "\"name\":\"echo\",\"input\":{\"text\":\"hi\"}}],"
            + "\"usage\":{\"input_tokens\":9,\"output_tokens\":5}}"
        );

        ModelResponse response = model.decodeResponse(json);

        assertNull(response.getAssistantMessage().getContent());
        assertEquals("echo", response.getAssistantMessage().getToolCalls().get(0).getName());
        assertEquals("{\"text\":\"hi\"}",
            response.getAssistantMessage().getToolCalls().get(0).getArguments());
        assertEquals(14, response.getUsage().getTotalTokens());
        assertEquals("tool_use", response.getMetadata().get("stopReason"));
    }

    @Test
    void assemblesAnthropicTextAndInputJsonDeltas() throws Exception {
        AnthropicChatModel model = AnthropicChatModel.builder()
            .model("test-model")
            .build();
        List<ModelStreamEvent> events = new ArrayList<ModelStreamEvent>();
        ModelStreamDecoder decoder = model.newStreamDecoder(events::add);

        decoder.onEvent(event("message_start", "{\"type\":\"message_start\","
            + "\"message\":{\"id\":\"msg-1\",\"model\":\"test-model\","
            + "\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}}"));
        decoder.onEvent(event("content_block_start", "{\"type\":\"content_block_start\","
            + "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        decoder.onEvent(event("content_block_delta", "{\"type\":\"content_block_delta\","
            + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}"));
        decoder.onEvent(event("content_block_start", "{\"type\":\"content_block_start\","
            + "\"index\":1,\"content_block\":{\"type\":\"tool_use\","
            + "\"id\":\"call-1\",\"name\":\"echo\",\"input\":{}}}"));
        decoder.onEvent(event("content_block_delta", "{\"type\":\"content_block_delta\","
            + "\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
            + "\"partial_json\":\"{\\\"text\\\":\\\"hi\\\"}\"}}"));
        decoder.onEvent(event("message_delta", "{\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"tool_use\"},"
            + "\"usage\":{\"output_tokens\":6}}"));

        ModelResponse response = decoder.finish();

        assertEquals("Hello", response.getAssistantMessage().getContent());
        assertEquals("{\"text\":\"hi\"}",
            response.getAssistantMessage().getToolCalls().get(0).getArguments());
        assertEquals(13, response.getUsage().getTotalTokens());
        assertEquals(5, events.size());
    }

    private static SseEvent event(String type, String data) {
        return new SseEvent(type, data, null);
    }
}
