package io.github.gitsilence.agent.observability;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends completed traces to an observability platform without blocking the
 * Agent execution thread. The bounded queue deliberately drops new traces
 * under sustained backpressure instead of slowing the Agent Loop.
 */
public final class PlatformTraceExporter
        implements AgentTraceExporter, AutoCloseable {

    private static final int ERROR_BODY_LIMIT_BYTES = 4_096;

    private final URL endpoint;
    private final String apiKey;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxPayloadBytes;
    private final long shutdownTimeoutMillis;
    private final AgentTraceJsonCodec codec;
    private final BlockingQueue<AgentTrace> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean sending = new AtomicBoolean();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicReference<String> lastError =
        new AtomicReference<String>("");
    private final Object drainMonitor = new Object();
    private final Thread worker;

    private PlatformTraceExporter(Builder builder) {
        this.endpoint = builder.endpoint;
        this.apiKey = builder.apiKey;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
        this.maxAttempts = builder.maxAttempts;
        this.retryDelayMillis = builder.retryDelayMillis;
        this.maxPayloadBytes = builder.maxPayloadBytes;
        this.shutdownTimeoutMillis = builder.shutdownTimeoutMillis;
        this.codec = new AgentTraceJsonCodec();
        this.queue = new ArrayBlockingQueue<AgentTrace>(builder.queueCapacity);
        this.worker = new Thread(this::runWorker, "agent-trace-exporter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public static Builder builder(String endpoint) {
        return new Builder(endpoint);
    }

    @Override
    public void export(AgentTrace trace) {
        Objects.requireNonNull(trace, "trace");
        if (closed.get()) {
            throw new IllegalStateException("Platform trace exporter is closed");
        }
        if (queue.offer(trace)) {
            acceptedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
            lastError.set("Trace queue is full; newest trace was dropped");
        }
    }

    /** Waits until all currently accepted traces have finished sending. */
    public boolean flush(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = timeout.toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (drainMonitor) {
            while (!queue.isEmpty() || sending.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                long millis = Math.max(1L,
                    TimeUnit.NANOSECONDS.toMillis(remaining));
                try {
                    drainMonitor.wait(millis);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.interrupt();
        try {
            worker.join(shutdownTimeoutMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            int abandoned = queue.size();
            if (abandoned > 0) droppedCount.addAndGet(abandoned);
            queue.clear();
            lastError.set("Exporter shutdown timed out");
        }
        signalDrain();
    }

    public long getAcceptedCount() { return acceptedCount.get(); }
    public long getSentCount() { return sentCount.get(); }
    public long getFailedCount() { return failedCount.get(); }
    public long getDroppedCount() { return droppedCount.get(); }
    public int getQueuedCount() { return queue.size(); }
    public String getLastError() { return lastError.get(); }
    public URL getEndpoint() { return endpoint; }

    private void runWorker() {
        while (!closed.get() || !queue.isEmpty()) {
            AgentTrace trace;
            try {
                trace = queue.poll(250L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                continue;
            }
            if (trace == null) {
                signalDrain();
                continue;
            }
            sending.set(true);
            try {
                sendWithRetry(trace);
                sentCount.incrementAndGet();
                lastError.set("");
            } catch (Throwable error) {
                failedCount.incrementAndGet();
                lastError.set(message(error));
            } finally {
                sending.set(false);
                signalDrain();
            }
        }
        signalDrain();
    }

    private void sendWithRetry(AgentTrace trace) throws IOException {
        byte[] payload = codec.toUtf8(trace);
        if (payload.length > maxPayloadBytes) {
            throw new IOException("Trace payload exceeds maxPayloadBytes: "
                + payload.length + " > " + maxPayloadBytes);
        }

        IOException failure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int status = send(payload);
                if (status >= 200 && status < 300) return;
                IOException responseError = new IOException(
                    "Platform returned HTTP " + status
                );
                if (!retryable(status) || attempt == maxAttempts) {
                    throw responseError;
                }
                failure = responseError;
            } catch (HttpStatusException error) {
                failure = error;
                if (!retryable(error.status) || attempt == maxAttempts) {
                    throw error;
                }
            } catch (IOException error) {
                failure = error;
                if (attempt == maxAttempts) throw error;
            }
            waitBeforeRetry();
        }
        throw failure == null
            ? new IOException("Platform trace delivery failed")
            : failure;
    }

    private int send(byte[] payload) throws IOException {
        HttpURLConnection connection =
            (HttpURLConnection) endpoint.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "agent-sdk-java/0.1");
            if (!apiKey.isEmpty()) {
                connection.setRequestProperty(
                    "Authorization", "Bearer " + apiKey
                );
            }
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String body = readErrorBody(connection);
                if (!body.isEmpty()) {
                    throw new HttpStatusException(status,
                        "Platform returned HTTP " + status + ": " + body);
                }
            }
            return status;
        } finally {
            connection.disconnect();
        }
    }

    private String readErrorBody(HttpURLConnection connection) {
        InputStream input = connection.getErrorStream();
        if (input == null) return "";
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int total = 0;
            int read;
            while (total < ERROR_BODY_LIMIT_BYTES
                    && (read = stream.read(buffer, 0, Math.min(
                        buffer.length, ERROR_BODY_LIMIT_BYTES - total))) != -1) {
                output.write(buffer, 0, read);
                total += read;
            }
            return new String(output.toByteArray(), "UTF-8");
        } catch (IOException ignored) {
            return "";
        }
    }

    private boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private void waitBeforeRetry() throws IOException {
        try {
            Thread.sleep(retryDelayMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Trace delivery retry was interrupted", error);
        }
    }

    private void signalDrain() {
        synchronized (drainMonitor) {
            drainMonitor.notifyAll();
        }
    }

    private String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
            ? error.getClass().getName()
            : value;
    }

    private static final class HttpStatusException extends IOException {
        private final int status;

        private HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class Builder {
        private final URL endpoint;
        private String apiKey = "";
        private int queueCapacity = 1_024;
        private int connectTimeoutMillis = 3_000;
        private int readTimeoutMillis = 5_000;
        private int maxAttempts = 3;
        private long retryDelayMillis = 200L;
        private int maxPayloadBytes = 2 * 1_024 * 1_024;
        private long shutdownTimeoutMillis = 5_000L;

        private Builder(String endpoint) {
            try {
                this.endpoint = new URL(requireText(endpoint, "endpoint"));
            } catch (MalformedURLException error) {
                throw new IllegalArgumentException("Invalid platform endpoint", error);
            }
            String protocol = this.endpoint.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                throw new IllegalArgumentException(
                    "Platform endpoint must use http or https"
                );
            }
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            return this;
        }

        public Builder queueCapacity(int value) {
            this.queueCapacity = positive(value, "queueCapacity");
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeoutMillis = durationMillis(value,
                "connectTimeout");
            return this;
        }

        public Builder readTimeout(Duration value) {
            this.readTimeoutMillis = durationMillis(value, "readTimeout");
            return this;
        }

        public Builder maxAttempts(int value) {
            this.maxAttempts = positive(value, "maxAttempts");
            return this;
        }

        public Builder retryDelay(Duration value) {
            this.retryDelayMillis = durationMillis(value, "retryDelay");
            return this;
        }

        public Builder maxPayloadBytes(int value) {
            this.maxPayloadBytes = positive(value, "maxPayloadBytes");
            return this;
        }

        public Builder shutdownTimeout(Duration value) {
            this.shutdownTimeoutMillis = durationMillis(value,
                "shutdownTimeout");
            return this;
        }

        public PlatformTraceExporter build() {
            return new PlatformTraceExporter(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static int durationMillis(Duration value, String name) {
            Objects.requireNonNull(value, name);
            long millis = value.toMillis();
            if (millis <= 0L || millis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                    name + " must be between 1ms and "
                        + Integer.MAX_VALUE + "ms"
                );
            }
            return (int) millis;
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }
    }
}
