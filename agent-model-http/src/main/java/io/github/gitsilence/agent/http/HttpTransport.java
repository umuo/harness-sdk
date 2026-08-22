package io.github.gitsilence.agent.http;

import java.util.concurrent.CompletableFuture;

public interface HttpTransport {

    CompletableFuture<HttpResponseData> post(HttpRequestData request);

    HttpStreamHandle postSse(HttpRequestData request, SseEventListener listener);
}
