package io.github.gitsilence.agent.openai;

import io.github.gitsilence.agent.http.HttpTransport;
import io.github.gitsilence.agent.http.JdkHttpTransport;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.stream.ModelStream;
import io.github.gitsilence.agent.model.stream.ModelStreamListener;
import io.github.gitsilence.agent.model.stream.StreamingChatModel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Backward-compatible name for {@link OpenAiCompatibleChatModel}.
 * New code may use the more explicit class name directly.
 */
public final class OpenAiChatModel implements StreamingChatModel {

    private final OpenAiCompatibleChatModel delegate;

    private OpenAiChatModel(Builder builder) {
        OpenAiCompatibleChatModel.Builder target = OpenAiCompatibleChatModel.builder()
            .baseUrl(builder.baseUrl)
            .model(builder.model)
            .apiKey(builder.apiKey)
            .connectTimeoutMillis(builder.connectTimeoutMillis)
            .readTimeoutMillis(builder.readTimeoutMillis)
            .transport(builder.transport);
        for (Map.Entry<String, String> header : builder.headers.entrySet()) {
            target.header(header.getKey(), header.getValue());
        }
        this.delegate = target.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletableFuture<ModelResponse> generate(ModelRequest request) {
        return delegate.generate(request);
    }

    @Override
    public ModelStream generateStream(ModelRequest request,
                                      ModelStreamListener listener) {
        return delegate.generateStream(request, listener);
    }

    public static final class Builder {
        private String baseUrl = OpenAiCompatibleChatModel.DEFAULT_BASE_URL;
        private String apiKey;
        private String model;
        private int connectTimeoutMillis = 10_000;
        private int readTimeoutMillis = 60_000;
        private HttpTransport transport = JdkHttpTransport.shared();
        private final Map<String, String> headers =
            new LinkedHashMap<String, String>();

        /**
         * Sets the API base URL, for example {@code https://api.openai.com/v1}.
         * The Chat Completions path is appended internally.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = requireText(baseUrl, "baseUrl");
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder connectTimeoutMillis(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("connectTimeoutMillis must be positive");
            }
            this.connectTimeoutMillis = value;
            return this;
        }

        public Builder readTimeoutMillis(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("readTimeoutMillis must be positive");
            }
            this.readTimeoutMillis = value;
            return this;
        }

        /**
         * Retained for source compatibility. The executor is adapted to the
         * generic Java 8 HTTP transport.
         */
        public Builder executor(Executor executor) {
            this.transport = new JdkHttpTransport(
                Objects.requireNonNull(executor, "executor")
            );
            return this;
        }

        public Builder transport(HttpTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(requireText(name, "header name"),
                Objects.requireNonNull(value, "header value"));
            return this;
        }

        public OpenAiChatModel build() {
            return new OpenAiChatModel(this);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
