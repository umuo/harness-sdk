package io.github.gitsilence.agent.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.stream.ModelStreamListener;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractHttpChatModelTest {

    @Test
    void cancellingModelFutureCancelsTransportRequest() {
        CompletableFuture<HttpResponseData> transportFuture =
            new CompletableFuture<HttpResponseData>();
        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponseData> post(HttpRequestData request) {
                return transportFuture;
            }

            @Override
            public HttpStreamHandle postSse(HttpRequestData request,
                                            SseEventListener listener) {
                throw new UnsupportedOperationException();
            }
        };
        AbstractHttpChatModel model = new TestModel(transport);
        ModelRequest request = new ModelRequest(
            Collections.emptyList(), Collections.emptyList(), ModelOptions.empty()
        );

        CompletableFuture<ModelResponse> result = model.generate(request);

        assertTrue(result.cancel(true));
        assertTrue(transportFuture.isCancelled());
    }

    private static final class TestModel extends AbstractHttpChatModel {
        private TestModel(HttpTransport transport) {
            super(
                transport,
                "http://localhost/test",
                Collections.emptyMap(),
                1000,
                1000
            );
        }

        @Override
        protected String providerName() {
            return "test";
        }

        @Override
        protected JsonNode encodeRequest(ModelRequest request, boolean stream) {
            return mapper().createObjectNode();
        }

        @Override
        protected ModelResponse decodeResponse(JsonNode response) {
            return ModelResponse.of(ChatMessage.assistant("ok"));
        }

        @Override
        protected ModelStreamDecoder newStreamDecoder(ModelStreamListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
