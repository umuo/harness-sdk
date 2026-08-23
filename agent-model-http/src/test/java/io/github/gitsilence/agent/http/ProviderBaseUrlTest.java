package io.github.gitsilence.agent.http;

import io.github.gitsilence.agent.anthropic.AnthropicChatModel;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.openai.OpenAiCompatibleChatModel;
import io.github.gitsilence.agent.openai.OpenAiResponsesChatModel;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderBaseUrlTest {

    private static final String CHAT_RESPONSE = "{\"choices\":[{\"message\":{"
        + "\"role\":\"assistant\",\"content\":\"ok\"}}]}";
    private static final String RESPONSES_RESPONSE = "{\"output\":[{"
        + "\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
        + "\"text\":\"ok\"}]}]}";
    private static final String ANTHROPIC_RESPONSE = "{\"content\":[{"
        + "\"type\":\"text\",\"text\":\"ok\"}]}";

    @Test
    void openAiChatModelsAppendChatCompletionsToVersionedBaseUrl() {
        CapturingTransport facadeTransport = new CapturingTransport(CHAT_RESPONSE);
        OpenAiChatModel.builder()
            .baseUrl("https://gateway.example/v1/")
            .model("test-model")
            .transport(facadeTransport)
            .build()
            .generate(request())
            .join();
        assertEquals(
            "https://gateway.example/v1/chat/completions",
            facadeTransport.request.getUrl()
        );

        CapturingTransport compatibleTransport = new CapturingTransport(CHAT_RESPONSE);
        OpenAiCompatibleChatModel.builder()
            .baseUrl("https://compatible.example/openai/v1")
            .model("test-model")
            .transport(compatibleTransport)
            .build()
            .generate(request())
            .join();
        assertEquals(
            "https://compatible.example/openai/v1/chat/completions",
            compatibleTransport.request.getUrl()
        );
    }

    @Test
    void responsesModelAppendsResponsesToVersionedBaseUrl() {
        CapturingTransport transport = new CapturingTransport(RESPONSES_RESPONSE);
        OpenAiResponsesChatModel.builder()
            .baseUrl("https://gateway.example/v1/")
            .model("test-model")
            .transport(transport)
            .build()
            .generate(request())
            .join();

        assertEquals("https://gateway.example/v1/responses", transport.request.getUrl());
    }

    @Test
    void anthropicModelAppendsVersionedMessagesPathToHostBaseUrl() {
        CapturingTransport transport = new CapturingTransport(ANTHROPIC_RESPONSE);
        AnthropicChatModel.builder()
            .baseUrl("https://claude-gateway.example/")
            .model("test-model")
            .transport(transport)
            .build()
            .generate(request())
            .join();

        assertEquals(
            "https://claude-gateway.example/v1/messages",
            transport.request.getUrl()
        );
    }

    private static ModelRequest request() {
        return new ModelRequest(
            Collections.singletonList(ChatMessage.user("hello")),
            Collections.emptyList(),
            ModelOptions.empty()
        );
    }

    private static final class CapturingTransport implements HttpTransport {
        private final String responseBody;
        private HttpRequestData request;

        private CapturingTransport(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public CompletableFuture<HttpResponseData> post(HttpRequestData request) {
            this.request = request;
            return CompletableFuture.completedFuture(new HttpResponseData(
                200,
                responseBody,
                Collections.emptyMap()
            ));
        }

        @Override
        public HttpStreamHandle postSse(HttpRequestData request,
                                        SseEventListener listener) {
            throw new UnsupportedOperationException("Streaming is not used in this test");
        }
    }
}
