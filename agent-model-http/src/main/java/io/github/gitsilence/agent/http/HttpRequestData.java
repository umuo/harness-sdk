package io.github.gitsilence.agent.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class HttpRequestData {

    private final String url;
    private final Map<String, String> headers;
    private final String body;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public HttpRequestData(String url,
                           Map<String, String> headers,
                           String body,
                           int connectTimeoutMillis,
                           int readTimeoutMillis) {
        this.url = Objects.requireNonNull(url, "url");
        this.headers = Collections.unmodifiableMap(
            new LinkedHashMap<String, String>(headers)
        );
        this.body = Objects.requireNonNull(body, "body");
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public String getUrl() { return url; }
    public Map<String, String> getHeaders() { return headers; }
    public String getBody() { return body; }
    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public int getReadTimeoutMillis() { return readTimeoutMillis; }
}
