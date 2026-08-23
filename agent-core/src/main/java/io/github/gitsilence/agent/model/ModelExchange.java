package io.github.gitsilence.agent.model;

import java.util.Objects;

/**
 * Provider-level HTTP exchange captured by a Model implementation.
 *
 * <p>Headers are intentionally excluded because they commonly contain API
 * keys. Endpoint values must not contain query strings or fragments.</p>
 */
public final class ModelExchange {

    private final String provider;
    private final String endpoint;
    private final boolean streaming;
    private final String requestBody;
    private final int responseStatus;
    private final String responseBody;
    private final String responseMediaType;
    private final boolean responseTruncated;

    public ModelExchange(String provider,
                         String endpoint,
                         boolean streaming,
                         String requestBody,
                         int responseStatus,
                         String responseBody,
                         String responseMediaType,
                         boolean responseTruncated) {
        this.provider = requireText(provider, "provider");
        this.endpoint = requireText(endpoint, "endpoint");
        this.streaming = streaming;
        this.requestBody = Objects.requireNonNull(requestBody, "requestBody");
        this.responseStatus = Math.max(0, responseStatus);
        this.responseBody = responseBody == null ? "" : responseBody;
        this.responseMediaType = requireText(
            responseMediaType, "responseMediaType"
        );
        this.responseTruncated = responseTruncated;
    }

    public String getProvider() { return provider; }
    public String getEndpoint() { return endpoint; }
    public boolean isStreaming() { return streaming; }
    public String getRequestBody() { return requestBody; }
    public int getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public String getResponseMediaType() { return responseMediaType; }
    public boolean isResponseTruncated() { return responseTruncated; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
