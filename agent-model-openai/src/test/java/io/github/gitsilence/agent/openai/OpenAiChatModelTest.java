package io.github.gitsilence.agent.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.stream.ModelStreamEvent;
import io.github.gitsilence.agent.http.ModelStreamDecoder;
import io.github.gitsilence.agent.http.SseEvent;
import io.github.gitsilence.agent.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAiChatModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsCoreMessagesAndToolsToChatCompletionsJson() {
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
            .model("test-model")
            .build();
        ToolCall call = new ToolCall("call-1", "echo", "{\"text\":\"hi\"}");
        ToolDefinition definition = ToolDefinition.builder()
            .name("echo")
            .description("Echoes text")
            .inputSchema("{\"type\":\"object\",\"properties\":{"
                + "\"text\":{\"type\":\"string\"}}}")
            .build();
        ModelRequest request = new ModelRequest(
            Arrays.asList(
                ChatMessage.user("say hi"),
                ChatMessage.assistant(null, Collections.singletonList(call)),
                ChatMessage.tool("call-1", "echo", "hi", false)
            ),
            Collections.singletonList(definition),
            ModelOptions.builder().temperature(0.2).maxTokens(100).build()
        );

        JsonNode json = model.encodeRequest(request, false);

        assertEquals("test-model", json.path("model").asText());
        assertEquals("call-1", json.path("messages").get(1)
            .path("tool_calls").get(0).path("id").asText());
        assertEquals("call-1", json.path("messages").get(2)
            .path("tool_call_id").asText());
        assertEquals("echo", json.path("tools").get(0)
            .path("function").path("name").asText());
        assertEquals(100, json.path("max_tokens").asInt());
    }

    @Test
    void mapsChatCompletionsToolCallResponseToCoreModel() throws Exception {
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
            .model("test-model")
            .build();
        JsonNode json = mapper.readTree("{"
            + "\"id\":\"response-1\","
            + "\"model\":\"test-model\","
            + "\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{"
            + "\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{"
            + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
            + "\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\\\"hi\\\"}\"}}]}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,\"total_tokens\":14}"
            + "}");

        ModelResponse response = model.decodeResponse(json);

        assertNull(response.getAssistantMessage().getContent());
        assertEquals("echo", response.getAssistantMessage().getToolCalls().get(0).getName());
        assertEquals("{\"text\":\"hi\"}",
            response.getAssistantMessage().getToolCalls().get(0).getArguments());
        assertEquals(14, response.getUsage().getTotalTokens());
        assertEquals("tool_calls", response.getMetadata().get("finishReason"));
    }

    @Test
    void assemblesTextAndToolArgumentsFromChatCompletionStream() throws Exception {
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
            .model("test-model")
            .build();
        List<ModelStreamEvent> events = new ArrayList<ModelStreamEvent>();
        ModelStreamDecoder decoder = model.newStreamDecoder(events::add);

        decoder.onEvent(new SseEvent(null, "{\"id\":\"response-1\"," 
            + "\"model\":\"test-model\",\"choices\":[{\"delta\":{"
            + "\"content\":\"Hello \"}}]}", null));
        decoder.onEvent(new SseEvent(null, "{\"choices\":[{\"delta\":{"
            + "\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
            + "\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\"}}]}}]}", null));
        decoder.onEvent(new SseEvent(null, "{\"choices\":[{\"finish_reason\":\"tool_calls\","
            + "\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{"
            + "\"arguments\":\"\\\"hi\\\"}\"}}]}}],\"usage\":{"
            + "\"prompt_tokens\":8,\"completion_tokens\":5,\"total_tokens\":13}}", null));
        decoder.onEvent(new SseEvent(null, "[DONE]", null));

        ModelResponse response = decoder.finish();

        assertEquals("Hello ", response.getAssistantMessage().getContent());
        assertEquals("echo", response.getAssistantMessage().getToolCalls().get(0).getName());
        assertEquals("{\"text\":\"hi\"}",
            response.getAssistantMessage().getToolCalls().get(0).getArguments());
        assertEquals(13, response.getUsage().getTotalTokens());
        assertEquals(6, events.size());
    }
}
