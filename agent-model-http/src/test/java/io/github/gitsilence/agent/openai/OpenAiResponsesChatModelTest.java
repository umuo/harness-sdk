package io.github.gitsilence.agent.openai;

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

class OpenAiResponsesChatModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsCoreConversationToResponsesInputItems() {
        OpenAiResponsesChatModel model = OpenAiResponsesChatModel.builder()
            .model("test-model")
            .build();
        ToolCall call = new ToolCall("call-1", "echo", "{\"text\":\"hi\"}");
        ToolDefinition definition = ToolDefinition.builder()
            .name("echo")
            .description("Echoes text")
            .inputSchema("{\"type\":\"object\"}")
            .build();
        ModelRequest request = new ModelRequest(
            Arrays.asList(
                ChatMessage.system("Be concise."),
                ChatMessage.user("say hi"),
                ChatMessage.assistant(null, Collections.singletonList(call)),
                ChatMessage.tool("call-1", "echo", "hi", false)
            ),
            Collections.singletonList(definition),
            ModelOptions.builder().maxTokens(120).build()
        );

        JsonNode json = model.encodeRequest(request, false);

        assertEquals("Be concise.", json.path("instructions").asText());
        assertEquals("input_text", json.path("input").get(0)
            .path("content").get(0).path("type").asText());
        assertEquals("function_call", json.path("input").get(1).path("type").asText());
        assertEquals("function_call_output", json.path("input").get(2).path("type").asText());
        assertEquals("echo", json.path("tools").get(0).path("name").asText());
        assertEquals(120, json.path("max_output_tokens").asInt());
    }

    @Test
    void decodesResponsesFunctionCall() throws Exception {
        OpenAiResponsesChatModel model = OpenAiResponsesChatModel.builder()
            .model("test-model")
            .build();
        JsonNode json = mapper.readTree("{"
            + "\"id\":\"resp-1\",\"model\":\"test-model\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call-1\","
            + "\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\\\"hi\\\"}\"}],"
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":4,\"total_tokens\":14}"
            + "}");

        ModelResponse response = model.decodeResponse(json);

        assertNull(response.getAssistantMessage().getContent());
        assertEquals("echo", response.getAssistantMessage().getToolCalls().get(0).getName());
        assertEquals(14, response.getUsage().getTotalTokens());
        assertEquals("completed", response.getMetadata().get("status"));
    }

    @Test
    void assemblesNamedResponsesStreamingEvents() throws Exception {
        OpenAiResponsesChatModel model = OpenAiResponsesChatModel.builder()
            .model("test-model")
            .build();
        List<ModelStreamEvent> events = new ArrayList<ModelStreamEvent>();
        ModelStreamDecoder decoder = model.newStreamDecoder(events::add);

        decoder.onEvent(event("response.created", "{\"type\":\"response.created\","
            + "\"response\":{\"id\":\"resp-1\",\"model\":\"test-model\"}}"));
        decoder.onEvent(event("response.output_text.delta", "{"
            + "\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}"));
        decoder.onEvent(event("response.output_item.added", "{"
            + "\"type\":\"response.output_item.added\",\"output_index\":1,"
            + "\"item\":{\"type\":\"function_call\",\"call_id\":\"call-1\","
            + "\"name\":\"echo\"}}"));
        decoder.onEvent(event("response.function_call_arguments.delta", "{"
            + "\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,"
            + "\"delta\":\"{\\\"text\\\":\\\"hi\\\"}\"}"));

        ModelResponse response = decoder.finish();

        assertEquals("Hello", response.getAssistantMessage().getContent());
        assertEquals("{\"text\":\"hi\"}",
            response.getAssistantMessage().getToolCalls().get(0).getArguments());
        assertEquals(4, events.size());
    }

    private static SseEvent event(String type, String data) {
        return new SseEvent(type, data, null);
    }
}
