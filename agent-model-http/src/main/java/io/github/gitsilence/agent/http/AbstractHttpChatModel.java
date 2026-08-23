package io.github.gitsilence.agent.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gitsilence.agent.model.ModelException;
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
        final HttpRequestData httpRequest;
        final CompletableFuture<HttpResponseData> httpResponse;
        try {
            httpRequest = request(request, false);
            httpResponse = transport.post(httpRequest);
            if (httpResponse == null) {
                return Futures.failed(new IllegalStateException(
                    "HTTP transport returned null future"
                ));
            }
        } catch (Throwable error) {
            return Futures.failed(error);
        }

        final CompletableFuture<ModelResponse> result =
            new CompletableFuture<ModelResponse>();
        httpResponse.whenComplete((response, error) -> {
            if (result.isCancelled()) {
                return;
            }
            if (error != null) {
                result.completeExceptionally(Futures.unwrap(error));
                return;
            }
            try {
                if (response == null) {
                    throw new ModelException("HTTP transport returned null response");
                }
                if (!response.isSuccessful()) {
                    throw new ModelException(
                        providerName() + " returned HTTP " + response.getStatus()
                            + ": " + truncate(response.getBody(), 4000)
                    );
                }
                result.complete(decodeResponse(mapper.readTree(response.getBody())));
            } catch (ModelException e) {
                result.completeExceptionally(e);
            } catch (Exception e) {
                result.completeExceptionally(new ModelException(
                    providerName() + " response decoding failed", e
                ));
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
        final HttpRequestData httpRequest;
        final ModelStreamDecoder decoder;
        try {
            httpRequest = request(request, true);
            decoder = newStreamDecoder(listener);
        } catch (Throwable error) {
            notifyError(listener, error);
            return new ModelStream(Futures.failed(error), () -> { });
        }
        final CompletableFuture<ModelResponse> completion =
            new CompletableFuture<ModelResponse>();
        final HttpStreamHandle stream;
        try {
            stream = transport.postSse(httpRequest, decoder::onEvent);
        } catch (Throwable error) {
            notifyError(listener, error);
            completion.completeExceptionally(error);
            return new ModelStream(completion, () -> { });
        }
        stream.completion().whenComplete((ignored, error) -> {
            if (completion.isCancelled()) {
                return;
            }
            if (error != null) {
                Throwable actual = Futures.unwrap(error);
                notifyError(listener, actual);
                completion.completeExceptionally(actual);
                return;
            }
            try {
                ModelResponse response = decoder.finish();
                listener.onComplete(response);
                completion.complete(response);
            } catch (Throwable finishError) {
                notifyError(listener, finishError);
                completion.completeExceptionally(finishError);
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
}
