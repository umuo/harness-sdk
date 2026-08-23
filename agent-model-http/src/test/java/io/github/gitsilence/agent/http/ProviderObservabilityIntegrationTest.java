package io.github.gitsilence.agent.http;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.observability.AgentSpan;
import io.github.gitsilence.agent.observability.AgentSpanKind;
import io.github.gitsilence.agent.observability.AgentTrace;
import io.github.gitsilence.agent.observability.AgentTraceJsonCodec;
import io.github.gitsilence.agent.observability.InMemoryTraceExporter;
import io.github.gitsilence.agent.openai.OpenAiCompatibleChatModel;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderObservabilityIntegrationTest {

    @Test
    void traceUsesTheActualOpenAiWireShapeAndKeepsSdkShapeSeparate() {
        String responseBody = "{\"id\":\"chatcmpl-wire\",\"model\":\"gpt-wire\","
            + "\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
            + "\"role\":\"assistant\",\"content\":\"wire answer\"}}],"
            + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,"
            + "\"total_tokens\":6}}";
        HttpTransport transport = new StaticTransport(responseBody);
        OpenAiCompatibleChatModel model = OpenAiCompatibleChatModel.builder()
            .baseUrl("https://api.example/v1")
            .apiKey("secret-header-value")
            .model("gpt-wire")
            .transport(transport)
            .build();
        InMemoryTraceExporter exporter = new InMemoryTraceExporter();
        AgentObservability observability = AgentObservability.builder()
            .exporter(exporter)
            .captureContent(true)
            .build();
        Agent agent = Agent.builder()
            .name("wire-agent")
            .description("wire-agent")
            .model(model)
            .plugin(observability)
            .build();

        assertEquals("wire answer", agent.run("hello wire").getOutput());

        AgentTrace trace = exporter.getTraces().get(0);
        AgentSpan span = trace.getSpans().stream()
            .filter(value -> value.getKind() == AgentSpanKind.MODEL)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing Model span"));
        assertEquals("gpt-wire", span.getInput().get("model"));
        assertTrue(span.getInput().containsKey("messages"));
        assertEquals("chatcmpl-wire", span.getOutput().get("id"));
        assertTrue(span.getOutput().containsKey("choices"));
        assertTrue(span.getSdkInput().containsKey("messages"));
        assertTrue(span.getSdkOutput().containsKey("message"));
        assertFalse(span.getInput().containsKey("messageCount"));
        assertEquals(
            "https://api.example/v1/chat/completions",
            span.getAttributes().get("agent.model.provider.endpoint")
        );
        String traceJson = new AgentTraceJsonCodec().toJson(trace);
        assertTrue(traceJson.contains("\"schemaVersion\":\"3\""));
        assertTrue(traceJson.contains("\"sdkInput\""));
        assertTrue(traceJson.contains("\"choices\""));
        assertFalse(traceJson.contains("secret-header-value"));
    }

    private static final class StaticTransport implements HttpTransport {
        private final String body;

        private StaticTransport(String body) {
            this.body = body;
        }

        @Override
        public CompletableFuture<HttpResponseData> post(
                HttpRequestData request) {
            return CompletableFuture.completedFuture(new HttpResponseData(
                200, body, Collections.emptyMap()
            ));
        }

        @Override
        public HttpStreamHandle postSse(HttpRequestData request,
                                        SseEventListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
