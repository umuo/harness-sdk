package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.Usage;
import io.github.gitsilence.agent.observability.AgentMetricsSnapshot;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.observability.AgentSpan;
import io.github.gitsilence.agent.observability.AgentSpanKind;
import io.github.gitsilence.agent.observability.AgentSpanStatus;
import io.github.gitsilence.agent.observability.AgentTrace;
import io.github.gitsilence.agent.observability.InMemoryTraceExporter;
import io.github.gitsilence.agent.runtime.AgentExecutionException;
import io.github.gitsilence.agent.runtime.ExecutionStatus;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.Tools;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentObservabilityTest {

    @Test
    void exportsHierarchicalSpansUsageAndMetricsWithoutContentByDefault() {
        InMemoryTraceExporter exporter = new InMemoryTraceExporter();
        AgentObservability observability = AgentObservability.builder()
            .exporter(exporter)
            .attribute("service.name", "observability-test")
            .build();
        AtomicInteger modelRound = new AtomicInteger();
        ChatModel model = request -> {
            if (modelRound.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(new ModelResponse(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall(
                            "call-1", "echo", "{\"text\":\"secret\"}"
                        )
                    )),
                    new Usage(10, 2, 12),
                    null
                ));
            }
            return CompletableFuture.completedFuture(new ModelResponse(
                ChatMessage.assistant("done"),
                new Usage(8, 3, 11),
                null
            ));
        };
        Tool echo = Tools.sync(
            ToolDefinition.builder().name("echo").description("Echo").build(),
            (arguments, context) -> ToolResult.success(
                arguments.requireString("text")
            )
        );
        Agent agent = agent("observed", model)
            .tool(echo)
            .plugin(observability)
            .build();

        AgentResult result = agent.run("run echo");

        assertEquals("done", result.getOutput());
        assertEquals(1, exporter.getTraces().size());
        AgentTrace trace = exporter.getTraces().get(0);
        assertEquals(ExecutionStatus.COMPLETED, trace.getStatus());
        assertEquals(2, trace.getStepCount());
        assertEquals(2, trace.getModelCallCount());
        assertEquals(1, trace.getToolCallCount());
        assertEquals(0, trace.getToolErrorCount());
        assertEquals(18, trace.getUsage().getInputTokens());
        assertEquals(5, trace.getUsage().getOutputTokens());
        assertEquals(23, trace.getUsage().getTotalTokens());
        assertEquals(6, trace.getSpans().size());
        assertEquals("observability-test",
            trace.getAttributes().get("service.name"));
        assertTrue(trace.getDurationNanos() >= 0L);

        AgentSpan turn = only(trace, AgentSpanKind.TURN, 0);
        AgentSpan firstStep = only(trace, AgentSpanKind.STEP, 0);
        AgentSpan firstModel = only(trace, AgentSpanKind.MODEL, 0);
        AgentSpan tool = only(trace, AgentSpanKind.TOOL, 0);
        assertEquals(turn.getSpanId(), firstStep.getParentSpanId());
        assertEquals(firstStep.getSpanId(), firstModel.getParentSpanId());
        assertEquals(firstStep.getSpanId(), tool.getParentSpanId());
        assertEquals(AgentSpanStatus.OK, tool.getStatus());
        assertFalse(tool.getAttributes().containsKey("agent.tool.arguments"));
        assertFalse(tool.getAttributes().containsKey("agent.tool.result"));
        assertFalse(firstModel.getAttributes().containsKey(
            "agent.model.input.messages"
        ));

        AgentMetricsSnapshot metrics = observability.metrics();
        assertEquals(1, metrics.getTurnsStarted());
        assertEquals(1, metrics.getTurnsCompleted());
        assertEquals(0, metrics.getActiveTurns());
        assertEquals(2, metrics.getSteps());
        assertEquals(2, metrics.getModelCalls());
        assertEquals(1, metrics.getToolCalls());
        assertEquals(23, metrics.getTotalTokens());
    }

    @Test
    void capturesAndBoundsSensitiveContentOnlyWhenEnabled() {
        InMemoryTraceExporter exporter = new InMemoryTraceExporter();
        AgentObservability observability = AgentObservability.builder()
            .exporter(exporter)
            .captureContent(true)
            .maxCapturedContentCharacters(128)
            .build();
        StringBuilder longInput = new StringBuilder("secret-");
        while (longInput.length() < 300) longInput.append('x');
        Agent agent = agent("capture", request ->
            CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("visible-response"))
            )
        ).plugin(observability).build();

        agent.run(longInput.toString());

        AgentSpan model = only(
            exporter.getTraces().get(0), AgentSpanKind.MODEL, 0
        );
        String captured = (String) model.getAttributes().get(
            "agent.model.input.messages"
        );
        assertTrue(captured.length() <= 128);
        assertTrue(captured.endsWith("...[truncated]"));
        assertTrue(String.valueOf(model.getAttributes().get(
            "agent.model.output.message"
        )).contains("visible-response"));
    }

    @Test
    void doesNotLeakUnstructuredToolErrorsWhenContentCaptureIsDisabled() {
        InMemoryTraceExporter exporter = new InMemoryTraceExporter();
        AgentObservability observability = AgentObservability.builder()
            .exporter(exporter)
            .build();
        AtomicInteger round = new AtomicInteger();
        ChatModel model = request -> {
            if (round.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall("call-error", "fail", "{}")
                    ))
                ));
            }
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("recovered"))
            );
        };
        Tool failingTool = Tools.sync(
            ToolDefinition.builder().name("fail").description("Fail").build(),
            (arguments, context) -> ToolResult.failure("private failure detail")
        );
        Agent agent = agent("tool-error", model)
            .tool(failingTool)
            .plugin(observability)
            .build();

        agent.run("run");

        AgentSpan tool = only(
            exporter.getTraces().get(0), AgentSpanKind.TOOL, 0
        );
        assertEquals(AgentSpanStatus.ERROR, tool.getStatus());
        assertEquals("Tool returned an error", tool.getErrorMessage());
        assertFalse(tool.getErrorMessage().contains("private failure detail"));
        assertFalse(tool.getAttributes().containsKey("agent.tool.result"));
        assertEquals(1, observability.metrics().getToolErrors());
    }

    @Test
    void exportsFailedOpenSpansAndIsolatesExporterFailures() {
        InMemoryTraceExporter exporter = new InMemoryTraceExporter();
        AgentObservability traces = AgentObservability.builder()
            .exporter(exporter)
            .build();
        CompletableFuture<ModelResponse> failed =
            new CompletableFuture<ModelResponse>();
        failed.completeExceptionally(new IllegalStateException("provider down"));
        Agent failing = agent("failing", request -> failed)
            .plugin(traces)
            .build();

        assertThrows(AgentExecutionException.class, () -> failing.run("fail"));

        AgentTrace trace = exporter.getTraces().get(0);
        assertEquals(ExecutionStatus.FAILED, trace.getStatus());
        assertTrue(trace.getErrorMessage().contains("provider down"));
        assertEquals(
            AgentSpanStatus.ERROR,
            only(trace, AgentSpanKind.MODEL, 0).getStatus()
        );
        assertEquals(1, traces.metrics().getTurnsFailed());

        AgentObservability brokenExporter = AgentObservability.builder()
            .exporter(value -> {
                throw new IllegalStateException("collector unavailable");
            })
            .build();
        Agent healthy = agent("healthy", request ->
            CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("ok"))
            )
        ).plugin(brokenExporter).build();

        assertEquals("ok", healthy.run("hello").getOutput());
        assertEquals(1, brokenExporter.metrics().getExporterFailures());
        assertEquals(1, brokenExporter.metrics().getTurnsCompleted());
    }

    @Test
    void correlatesParentAndChildAgentTraces() {
        InMemoryTraceExporter exporter = new InMemoryTraceExporter();
        AgentObservability shared = AgentObservability.builder()
            .exporter(exporter)
            .build();
        Agent child = agent("research", request ->
            CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("research result"))
            )
        ).plugin(shared).build();
        AtomicInteger parentRound = new AtomicInteger();
        Agent parent = agent("supervisor", request -> {
            if (parentRound.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall(
                            "delegate-1", "research", "{\"task\":\"find it\"}"
                        )
                    ))
                ));
            }
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("final"))
            );
        }).tool(child).plugin(shared).build();

        parent.run("delegate");

        assertEquals(2, exporter.getTraces().size());
        AgentTrace childTrace = traceFor(exporter.getTraces(), "research");
        AgentTrace parentTrace = traceFor(exporter.getTraces(), "supervisor");
        assertEquals(parentTrace.getTraceId(), childTrace.getTraceId());
        assertEquals(parentTrace.getTurnId(), childTrace.getParentTurnId());
        assertEquals(
            only(parentTrace, AgentSpanKind.TOOL, 0).getSpanId(),
            childTrace.getParentSpanId()
        );
        assertEquals(
            childTrace.getParentSpanId(),
            only(childTrace, AgentSpanKind.TURN, 0).getParentSpanId()
        );
    }

    private static AgentSpan only(AgentTrace trace,
                                  AgentSpanKind kind,
                                  int index) {
        int current = 0;
        for (AgentSpan span : trace.getSpans()) {
            if (span.getKind() == kind) {
                if (current == index) return span;
                current++;
            }
        }
        throw new AssertionError("Missing span " + kind + " at " + index);
    }

    private static AgentTrace traceFor(List<AgentTrace> traces,
                                       String agentName) {
        for (AgentTrace trace : traces) {
            if (agentName.equals(trace.getAgentName())) return trace;
        }
        throw new AssertionError("Missing trace for " + agentName);
    }

    private static io.github.gitsilence.agent.agent.AgentBuilder agent(
            String name,
            ChatModel model) {
        return Agent.builder()
            .name(name)
            .description(name)
            .model(model);
    }
}
