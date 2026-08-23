package io.github.gitsilence.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.observability.AgentObservabilityMode;
import io.github.gitsilence.agent.observability.LoggingTraceExporter;
import io.github.gitsilence.agent.observability.PlatformTraceExporter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityExportersTest {

    @Test
    void offModeDoesNotAssembleTracesOrMetrics() {
        AgentObservability observability = AgentObservability.disabled();
        Agent agent = agent("off", successModel()).plugin(observability).build();

        assertEquals("ok", agent.run("hello").getOutput());
        assertEquals(AgentObservabilityMode.OFF, observability.getMode());
        assertFalse(observability.isEnabled());
        assertEquals(0L, observability.metrics().getTurnsStarted());
        assertEquals(0, observability.getActiveTurnCount());
    }

    @Test
    void loggingModeWritesStableJson() throws Exception {
        Logger logger = Logger.getLogger("observability-test-logger");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        AtomicReference<String> message = new AtomicReference<String>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                message.set(record.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            AgentObservability observability = AgentObservability.builder()
                .logging(new LoggingTraceExporter(logger))
                .build();
            agent("logged", successModel()).plugin(observability).build()
                .run("hello");

            JsonNode json = new ObjectMapper().readTree(message.get());
            assertEquals("2", json.path("schemaVersion").asText());
            assertEquals("logged", json.path("agentName").asText());
            assertEquals("COMPLETED", json.path("status").asText());
            assertTrue(json.path("spans").isArray());
            assertEquals(AgentObservabilityMode.LOGGING,
                observability.getMode());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void platformModeSendsAsynchronouslyWithAuthAndRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<String>();
        AtomicReference<String> body = new AtomicReference<String>();
        HttpServer server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 0
        );
        server.createContext("/api/traces", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                attempts.incrementAndGet();
                authorization.set(exchange.getRequestHeaders().getFirst(
                    "Authorization"
                ));
                body.set(readUtf8(exchange.getRequestBody()));
                int status = attempts.get() == 1 ? 503 : 202;
                byte[] response = (status == 503 ? "retry" : "accepted")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });
        server.start();

        PlatformTraceExporter exporter = PlatformTraceExporter.builder(
            "http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/traces"
        ).apiKey("test-key")
            .retryDelay(Duration.ofMillis(10))
            .build();
        AgentObservability observability = AgentObservability.builder()
            .platform(exporter)
            .attribute("service.name", "agent-test")
            .build();
        try {
            agent("platform", successModel()).plugin(observability).build()
                .run("hello");

            assertTrue(exporter.flush(Duration.ofSeconds(5)));
            assertEquals(2, attempts.get());
            assertEquals("Bearer test-key", authorization.get());
            JsonNode json = new ObjectMapper().readTree(body.get());
            assertEquals("2", json.path("schemaVersion").asText());
            assertEquals("platform", json.path("agentName").asText());
            assertEquals("agent-test",
                json.path("attributes").path("service.name").asText());
            assertEquals(1L, exporter.getAcceptedCount());
            assertEquals(1L, exporter.getSentCount());
            assertEquals(0L, exporter.getFailedCount());
            assertEquals(AgentObservabilityMode.PLATFORM,
                observability.getMode());
        } finally {
            observability.close();
            server.stop(0);
        }
    }

    private static ChatModel successModel() {
        return request -> CompletableFuture.completedFuture(
            ModelResponse.of(ChatMessage.assistant("ok"))
        );
    }

    private static io.github.gitsilence.agent.agent.AgentBuilder agent(
            String name,
            ChatModel model) {
        return Agent.builder().name(name).description(name).model(model);
    }

    private static String readUtf8(InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1_024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
