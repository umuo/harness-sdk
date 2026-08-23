package io.github.gitsilence.agent.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.model.ModelExchangeException;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.stream.ModelStreamListener;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractHttpChatModelTest {

    @Test
    void attachesActualRequestAndResponseBodiesWithoutUrlSecrets() {
        String responseBody = "{\"wire\":\"response\"}";
        HttpTransport transport = completedTransport(200, responseBody);
        ModelResponse response = new TestModel(
            transport, "https://provider.example/v1/test?api_key=secret#part"
        ).generate(request()).join();

        assertEquals("{\"stream\":false,\"wire\":\"request\"}",
            response.getExchange().getRequestBody());
        assertEquals(responseBody, response.getExchange().getResponseBody());
        assertEquals(200, response.getExchange().getResponseStatus());
        assertEquals("https://provider.example/v1/test",
            response.getExchange().getEndpoint());
        assertFalse(response.getExchange().isStreaming());
    }

    @Test
    void doesNotRetainProviderBodiesUnlessRequestOptsIn() {
        ModelRequest request = new ModelRequest(
            Collections.emptyList(),
            Collections.emptyList(),
            ModelOptions.empty()
        );
        ModelResponse response = new TestModel(completedTransport(
            200, "{\"wire\":\"response\"}"
        )).generate(request).join();

        assertNull(response.getExchange());
    }

    @Test
    void attachesNormalizedRawSseBodyToStreamingResponse() {
        String raw = "event: response.output_text.delta\n"
            + "data: {\"delta\":\"hello\"}\n\n";
        HttpTransport transport = new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponseData> post(
                    HttpRequestData request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public HttpStreamHandle postSse(HttpRequestData request,
                                            SseEventListener listener) {
                try {
                    listener.onEvent(new SseEvent(
                        "response.output_text.delta",
                        "{\"delta\":\"hello\"}",
                        null,
                        raw
                    ));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
                return new HttpStreamHandle(
                    CompletableFuture.completedFuture(null), () -> { }
                );
            }
        };

        ModelResponse response = new TestModel(transport)
            .generateStream(request(), new ModelStreamListener() {
                @Override
                public void onEvent(
                        io.github.gitsilence.agent.model.stream.ModelStreamEvent event) {
                }
            })
            .completion()
            .join();

        assertTrue(response.getExchange().isStreaming());
        assertEquals("text/event-stream",
            response.getExchange().getResponseMediaType());
        assertEquals(raw, response.getExchange().getResponseBody());
        assertTrue(response.getExchange().getRequestBody().contains(
            "\"stream\":true"
        ));
    }

    @Test
    void failedHttpResponseCarriesRequestAndErrorResponseBody() {
        CompletionException completion = assertThrows(
            CompletionException.class,
            () -> new TestModel(completedTransport(
                429, "{\"error\":{\"message\":\"rate limited\"}}"
            )).generate(request()).join()
        );
        assertTrue(completion.getCause() instanceof ModelExchangeException);
        ModelExchangeException error =
            (ModelExchangeException) completion.getCause();
        assertEquals(429, error.getExchange().getResponseStatus());
        assertEquals("{\"error\":{\"message\":\"rate limited\"}}",
            error.getExchange().getResponseBody());
        assertTrue(error.getExchange().getRequestBody().contains(
            "\"wire\":\"request\""
        ));
    }

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

        CompletableFuture<ModelResponse> result = model.generate(request());

        assertTrue(result.cancel(true));
        assertTrue(transportFuture.isCancelled());
    }

    private static final class TestModel extends AbstractHttpChatModel {
        private TestModel(HttpTransport transport) {
            this(transport, "http://localhost/test");
        }

        private TestModel(HttpTransport transport, String endpoint) {
            super(
                transport,
                endpoint,
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
            return mapper().createObjectNode()
                .put("stream", stream)
                .put("wire", "request");
        }

        @Override
        protected ModelResponse decodeResponse(JsonNode response) {
            return ModelResponse.of(ChatMessage.assistant("ok"));
        }

        @Override
        protected ModelStreamDecoder newStreamDecoder(ModelStreamListener listener) {
            return new ModelStreamDecoder() {
                @Override
                public void onEvent(SseEvent event) { }

                @Override
                public ModelResponse finish() {
                    return ModelResponse.of(ChatMessage.assistant("ok"));
                }
            };
        }
    }

    private static ModelRequest request() {
        return new ModelRequest(
            Collections.emptyList(),
            Collections.emptyList(),
            ModelOptions.empty(),
            true
        );
    }

    private static HttpTransport completedTransport(int status, String body) {
        return new HttpTransport() {
            @Override
            public CompletableFuture<HttpResponseData> post(
                    HttpRequestData request) {
                return CompletableFuture.completedFuture(new HttpResponseData(
                    status, body, Collections.emptyMap()
                ));
            }

            @Override
            public HttpStreamHandle postSse(HttpRequestData request,
                                            SseEventListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
