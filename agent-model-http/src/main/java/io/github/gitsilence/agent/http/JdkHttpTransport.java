package io.github.gitsilence.agent.http;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 Java 8 {@link HttpURLConnection} 的 JSON POST/SSE 传输实现。
 *
 * <p>阻塞网络 I/O 被放到执行器中，对外仍暴露 CompletableFuture。取消 future 会
 * 断开当前连接，从而尽快解除正在阻塞的读操作。</p>
 */
public final class JdkHttpTransport implements HttpTransport {

    private static final JdkHttpTransport SHARED = new JdkHttpTransport(
        Executors.newCachedThreadPool(new DaemonThreadFactory())
    );

    private final Executor executor;

    public JdkHttpTransport(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public static JdkHttpTransport shared() {
        return SHARED;
    }

    @Override
    public CompletableFuture<HttpResponseData> post(final HttpRequestData request) {
        final CompletableFuture<HttpResponseData> result =
            new CompletableFuture<HttpResponseData>();
        // 保存活动连接，让任意线程上的取消都能触发 disconnect。
        final AtomicReference<HttpURLConnection> active =
            new AtomicReference<HttpURLConnection>();
        executor.execute(() -> {
            if (result.isCancelled()) {
                return;
            }
            HttpURLConnection connection = null;
            try {
                connection = open(request, false);
                active.set(connection);
                if (result.isCancelled()) {
                    return;
                }
                writeBody(connection, request.getBody());
                int status = connection.getResponseCode();
                String body = readUtf8(responseStream(connection, status));
                result.complete(new HttpResponseData(
                    status, body, connection.getHeaderFields()
                ));
            } catch (Throwable error) {
                if (!result.isCancelled()) {
                    result.completeExceptionally(error);
                }
            } finally {
                active.set(null);
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                disconnect(active.get());
            }
        });
        return result;
    }

    @Override
    public HttpStreamHandle postSse(final HttpRequestData request,
                                    final SseEventListener listener) {
        final CompletableFuture<Void> completion = new CompletableFuture<Void>();
        final AtomicReference<HttpURLConnection> active =
            new AtomicReference<HttpURLConnection>();
        executor.execute(() -> {
            if (completion.isCancelled()) {
                return;
            }
            HttpURLConnection connection = null;
            try {
                connection = open(request, true);
                active.set(connection);
                if (completion.isCancelled()) {
                    return;
                }
                writeBody(connection, request.getBody());
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new HttpTransportException(
                        status, readUtf8(responseStream(connection, status))
                    );
                }
                parseSse(connection.getInputStream(), listener);
                completion.complete(null);
            } catch (Throwable error) {
                if (!completion.isCancelled()) {
                    completion.completeExceptionally(error);
                }
            } finally {
                active.set(null);
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
        return new HttpStreamHandle(completion, () -> disconnect(active.get()));
    }

    private static HttpURLConnection open(HttpRequestData request,
                                          boolean streaming) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
            URI.create(request.getUrl()).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(request.getConnectTimeoutMillis());
        connection.setReadTimeout(request.getReadTimeoutMillis());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty(
            "Accept", streaming ? "text/event-stream" : "application/json"
        );
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    private static void writeBody(HttpURLConnection connection,
                                  String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }
    }

    private static InputStream responseStream(HttpURLConnection connection,
                                              int status) throws Exception {
        InputStream stream = status >= 200 && status < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        return stream;
    }

    private static String readUtf8(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void parseSse(InputStream input,
                         SseEventListener listener) throws Exception {
        // SSE 以空行分隔事件；连续 data 行用换行拼接，注释行以 ':' 开头。
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String event = null;
            String id = null;
            StringBuilder data = new StringBuilder();
            StringBuilder raw = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    raw.append('\n');
                    if (data.length() > 0 || event != null) {
                        listener.onEvent(new SseEvent(
                            event, data.toString(), id, raw.toString()
                        ));
                    }
                    event = null;
                    id = null;
                    data.setLength(0);
                    raw.setLength(0);
                    continue;
                }
                raw.append(line).append('\n');
                if (line.charAt(0) == ':') {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if ("event".equals(field)) {
                    event = value;
                } else if ("data".equals(field)) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(value);
                } else if ("id".equals(field)) {
                    id = value;
                }
            }
            if (data.length() > 0 || event != null) {
                listener.onEvent(new SseEvent(
                    event, data.toString(), id, raw.toString()
                ));
            }
        }
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            connection.disconnect();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                runnable, "agent-http-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}
