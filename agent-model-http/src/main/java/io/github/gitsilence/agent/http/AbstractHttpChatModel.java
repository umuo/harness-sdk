package io.github.gitsilence.agent.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gitsilence.agent.model.ModelException;
import io.github.gitsilence.agent.model.ModelExchange;
import io.github.gitsilence.agent.model.ModelExchangeException;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.stream.ModelStream;
import io.github.gitsilence.agent.model.stream.ModelStreamListener;
import io.github.gitsilence.agent.model.stream.StreamingChatModel;
import io.github.gitsilence.agent.runtime.Futures;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractHttpChatModel implements StreamingChatModel {

    private static final int MAX_EXCHANGE_RESPONSE_CHARACTERS = 2 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final HttpTransport transport;
    private final String endpoint;
    private final Map<String, String> headers;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    protected AbstractHttpChatModel(HttpTransport transport,
                                    String endpoint,
                                    Map<String, String> headers,
                                    int connectTimeoutMillis,
                                    int readTimeoutMillis) {
        this.mapper = new ObjectMapper();
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = requireText(endpoint, "endpoint");
        this.headers = Collections.unmodifiableMap(
            new LinkedHashMap<String, String>(headers)
        );
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public CompletableFuture<ModelResponse> generate(ModelRequest request) {
        final boolean captureExchange =
            request.isModelExchangeCaptureEnabled();
        final HttpRequestData httpRequest;
        try {
            httpRequest = request(request, false);
        } catch (Throwable error) {
            return Futures.failed(error);
        }
        final CompletableFuture<HttpResponseData> httpResponse;
        try {
            httpResponse = transport.post(httpRequest);
            if (httpResponse == null) {
                Throwable failure = new IllegalStateException(
                    "HTTP transport returned null future"
                );
                return Futures.failed(exchangeFailure(
                    captureExchange,
                    "HTTP transport returned null future",
                    failure,
                    httpRequest,
                    false
                ));
            }
        } catch (Throwable error) {
            return Futures.failed(exchangeFailure(
                captureExchange,
                providerName() + " transport failed",
                error,
                httpRequest,
                false
            ));
        }

        final CompletableFuture<ModelResponse> result =
            new CompletableFuture<ModelResponse>();
        httpResponse.whenComplete((response, error) -> {
            if (result.isCancelled()) {
                return;
            }
            if (error != null) {
                Throwable actual = Futures.unwrap(error);
                result.completeExceptionally(exchangeFailure(
                    captureExchange,
                    providerName() + " transport failed",
                    actual,
                    httpRequest,
                    false
                ));
                return;
            }
            ModelExchange exchange = null;
            try {
                if (response == null) {
                    if (captureExchange) {
                        exchange = exchange(
                            httpRequest,
                            false,
                            0,
                            "",
                            "application/json",
                            false
                        );
                        throw new ModelExchangeException(
                            "HTTP transport returned null response", exchange
                        );
                    }
                    throw new ModelException(
                        "HTTP transport returned null response"
                    );
                }
                if (captureExchange) {
                    exchange = exchange(
                        httpRequest,
                        false,
                        response.getStatus(),
                        response.getBody(),
                        "application/json",
                        false
                    );
                }
                if (!response.isSuccessful()) {
                    String message = providerName() + " returned HTTP "
                        + response.getStatus() + ": "
                        + truncate(response.getBody(), 4000);
                    if (captureExchange) {
                        throw new ModelExchangeException(message, exchange);
                    }
                    throw new ModelException(message);
                }
                ModelResponse decoded = decodeResponse(
                    mapper.readTree(response.getBody())
                );
                result.complete(captureExchange
                    ? decoded.withExchange(exchange) : decoded);
            } catch (ModelExchangeException e) {
                result.completeExceptionally(e);
            } catch (ModelException e) {
                result.completeExceptionally(captureExchange
                    ? new ModelExchangeException(e.getMessage(), e, exchange)
                    : e);
            } catch (Exception e) {
                String message = providerName()
                    + " response decoding failed";
                result.completeExceptionally(captureExchange
                    ? new ModelExchangeException(message, e, exchange)
                    : new ModelException(message, e));
            }
        });
        result.whenComplete((response, error) -> {
            if (result.isCancelled()) {
                httpResponse.cancel(true);
            }
        });
        return result;
    }

    @Override
    public ModelStream generateStream(ModelRequest request,
                                      final ModelStreamListener listener) {
        Objects.requireNonNull(listener, "listener");
        final boolean captureExchange =
            request.isModelExchangeCaptureEnabled();
        final HttpRequestData httpRequest;
        try {
            httpRequest = request(request, true);
        } catch (Throwable error) {
            notifyError(listener, error);
            return new ModelStream(Futures.failed(error), () -> { });
        }
        final ModelStreamDecoder decoder;
        try {
            decoder = newStreamDecoder(listener);
        } catch (Throwable error) {
            Throwable exchanged = exchangeFailure(
                captureExchange,
                providerName() + " streaming decoder initialization failed",
                error,
                httpRequest,
                true
            );
            notifyError(listener, exchanged);
            return new ModelStream(Futures.failed(exchanged), () -> { });
        }
        final CompletableFuture<ModelResponse> completion =
            new CompletableFuture<ModelResponse>();
        final BoundedSseCapture responseCapture = new BoundedSseCapture(
            MAX_EXCHANGE_RESPONSE_CHARACTERS
        );
        final HttpStreamHandle stream;
        try {
            stream = transport.postSse(httpRequest, event -> {
                if (captureExchange) responseCapture.append(event);
                decoder.onEvent(event);
            });
            if (stream == null) {
                throw new IllegalStateException(
                    "HTTP transport returned null stream"
                );
            }
        } catch (Throwable error) {
            Throwable actual = exchangeFailure(
                captureExchange,
                providerName() + " streaming transport failed",
                error,
                httpRequest,
                true
            );
            notifyError(listener, actual);
            completion.completeExceptionally(actual);
            return new ModelStream(completion, () -> { });
        }
        stream.completion().whenComplete((ignored, error) -> {
            if (completion.isCancelled()) {
                return;
            }
            if (error != null) {
                Throwable actual = Futures.unwrap(error);
                Throwable exchanged = exchangeFailure(
                    captureExchange,
                    providerName() + " streaming request failed",
                    actual,
                    httpRequest,
                    true,
                    responseCapture.body(),
                    responseCapture.isTruncated()
                );
                notifyError(listener, exchanged);
                completion.completeExceptionally(exchanged);
                return;
            }
            try {
                ModelResponse decoded = decoder.finish();
                ModelResponse response;
                if (captureExchange) {
                    ModelExchange exchange = exchange(
                        httpRequest,
                        true,
                        0,
                        responseCapture.body(),
                        "text/event-stream",
                        responseCapture.isTruncated()
                    );
                    response = decoded.withExchange(exchange);
                } else {
                    response = decoded;
                }
                listener.onComplete(response);
                completion.complete(response);
            } catch (Throwable finishError) {
                Throwable exchanged = exchangeFailure(
                    captureExchange,
                    providerName() + " streaming response decoding failed",
                    finishError,
                    httpRequest,
                    true,
                    responseCapture.body(),
                    responseCapture.isTruncated()
                );
                notifyError(listener, exchanged);
                completion.completeExceptionally(exchanged);
            }
        });
        return new ModelStream(completion, stream::cancel);
    }

    protected ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Appends a provider-owned API path to a caller-supplied base URL.
     * The base URL may contain a path prefix and may end with a slash.
     */
    protected static String resolveEndpoint(String baseUrl, String apiPath) {
        String normalizedBase = requireText(baseUrl, "baseUrl").trim();
        String normalizedPath = requireText(apiPath, "apiPath").trim();
        while (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        if (normalizedBase.isEmpty() || normalizedPath.isEmpty()) {
            throw new IllegalArgumentException("baseUrl and apiPath must not be blank");
        }
        return normalizedBase + '/' + normalizedPath;
    }

    protected abstract String providerName();

    protected abstract JsonNode encodeRequest(ModelRequest request, boolean stream);

    protected abstract ModelResponse decodeResponse(JsonNode response);

    protected abstract ModelStreamDecoder newStreamDecoder(ModelStreamListener listener);

    private HttpRequestData request(ModelRequest request, boolean stream) {
        try {
            return new HttpRequestData(
                endpoint,
                headers,
                mapper.writeValueAsString(encodeRequest(request, stream)),
                connectTimeoutMillis,
                readTimeoutMillis
            );
        } catch (Exception e) {
            throw new ModelException(providerName() + " request encoding failed", e);
        }
    }

    private ModelExchange exchange(HttpRequestData request,
                                   boolean streaming,
                                   int responseStatus,
                                   String responseBody,
                                   String responseMediaType,
                                   boolean responseTruncated) {
        String capturedResponse = responseBody == null ? "" : responseBody;
        boolean truncated = responseTruncated;
        if (capturedResponse.length() > MAX_EXCHANGE_RESPONSE_CHARACTERS) {
            capturedResponse = capturedResponse.substring(
                0, MAX_EXCHANGE_RESPONSE_CHARACTERS
            );
            truncated = true;
        }
        return new ModelExchange(
            providerName(),
            safeEndpoint(request.getUrl()),
            streaming,
            request.getBody(),
            responseStatus,
            capturedResponse,
            responseMediaType,
            truncated
        );
    }

    private Throwable exchangeFailure(boolean captureExchange,
                                      String message,
                                      Throwable cause,
                                      HttpRequestData request,
                                      boolean streaming) {
        if (!captureExchange) return cause;
        if (cause instanceof ModelExchangeException) return cause;
        int status = cause instanceof HttpTransportException
            ? ((HttpTransportException) cause).getStatus() : 0;
        String body = cause instanceof HttpTransportException
            ? ((HttpTransportException) cause).getResponseBody() : "";
        String mediaType = streaming && status == 0
            ? "text/event-stream" : "application/json";
        return new ModelExchangeException(
            message,
            cause,
            exchange(request, streaming, status, body, mediaType, false)
        );
    }

    private Throwable exchangeFailure(boolean captureExchange,
                                      String message,
                                      Throwable cause,
                                      HttpRequestData request,
                                      boolean streaming,
                                      String responseBody,
                                      boolean responseTruncated) {
        if (!captureExchange) return cause;
        if (cause instanceof ModelExchangeException) return cause;
        if (cause instanceof HttpTransportException) {
            return exchangeFailure(
                true, message, cause, request, streaming
            );
        }
        return new ModelExchangeException(
            message,
            cause,
            exchange(
                request,
                streaming,
                0,
                responseBody,
                "text/event-stream",
                responseTruncated
            )
        );
    }

    private static String safeEndpoint(String value) {
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = value.length();
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return value.substring(0, end);
    }

    private static void notifyError(ModelStreamListener listener, Throwable error) {
        try {
            listener.onError(error);
        } catch (Throwable ignored) {
            // Listener failures must not replace the transport or decoder failure.
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String truncate(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static final class BoundedSseCapture {
        private final int maximum;
        private final StringBuilder body = new StringBuilder();
        private boolean truncated;

        private BoundedSseCapture(int maximum) {
            this.maximum = maximum;
        }

        private void append(SseEvent event) {
            if (truncated) return;
            String value = event.getRaw().isEmpty()
                ? reconstruct(event) : event.getRaw();
            int remaining = maximum - body.length();
            if (value.length() <= remaining) {
                body.append(value);
                return;
            }
            if (remaining > 0) body.append(value, 0, remaining);
            truncated = true;
        }

        private String body() {
            return body.toString();
        }

        private boolean isTruncated() {
            return truncated;
        }

        private static String reconstruct(SseEvent event) {
            StringBuilder value = new StringBuilder();
            if (event.getId() != null) {
                value.append("id: ").append(event.getId()).append('\n');
            }
            if (!"message".equals(event.getEvent())) {
                value.append("event: ").append(event.getEvent()).append('\n');
            }
            String[] lines = event.getData().split("\\n", -1);
            for (String line : lines) {
                value.append("data: ").append(line).append('\n');
            }
            return value.append('\n').toString();
        }
    }
}
