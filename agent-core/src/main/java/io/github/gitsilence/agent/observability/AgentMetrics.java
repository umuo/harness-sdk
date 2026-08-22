package io.github.gitsilence.agent.observability;

import java.util.concurrent.atomic.AtomicLong;

final class AgentMetrics {

    private final AtomicLong turnsStarted = new AtomicLong();
    private final AtomicLong turnsCompleted = new AtomicLong();
    private final AtomicLong turnsStopped = new AtomicLong();
    private final AtomicLong turnsFailed = new AtomicLong();
    private final AtomicLong turnsCancelled = new AtomicLong();
    private final AtomicLong steps = new AtomicLong();
    private final AtomicLong modelCalls = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong toolErrors = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong turnDurationNanos = new AtomicLong();
    private final AtomicLong modelDurationNanos = new AtomicLong();
    private final AtomicLong toolDurationNanos = new AtomicLong();
    private final AtomicLong exporterFailures = new AtomicLong();

    void incrementTurnsStarted() {
        turnsStarted.incrementAndGet();
    }

    void incrementExporterFailures() {
        exporterFailures.incrementAndGet();
    }

    void record(AgentTrace trace) {
        switch (trace.getStatus()) {
            case COMPLETED:
                turnsCompleted.incrementAndGet();
                break;
            case STOPPED:
                turnsStopped.incrementAndGet();
                break;
            case FAILED:
                turnsFailed.incrementAndGet();
                break;
            case CANCELLED:
                turnsCancelled.incrementAndGet();
                break;
            default:
                break;
        }
        steps.addAndGet(trace.getStepCount());
        modelCalls.addAndGet(trace.getModelCallCount());
        toolCalls.addAndGet(trace.getToolCallCount());
        toolErrors.addAndGet(trace.getToolErrorCount());
        inputTokens.addAndGet(trace.getUsage().getInputTokens());
        outputTokens.addAndGet(trace.getUsage().getOutputTokens());
        totalTokens.addAndGet(trace.getUsage().getTotalTokens());
        turnDurationNanos.addAndGet(trace.getDurationNanos());
        for (AgentSpan span : trace.getSpans()) {
            if (span.getKind() == AgentSpanKind.MODEL) {
                modelDurationNanos.addAndGet(span.getDurationNanos());
            } else if (span.getKind() == AgentSpanKind.TOOL) {
                toolDurationNanos.addAndGet(span.getDurationNanos());
            }
        }
    }

    AgentMetricsSnapshot snapshot(long activeTurns) {
        return new AgentMetricsSnapshot(
            turnsStarted.get(),
            turnsCompleted.get(),
            turnsStopped.get(),
            turnsFailed.get(),
            turnsCancelled.get(),
            activeTurns,
            steps.get(),
            modelCalls.get(),
            toolCalls.get(),
            toolErrors.get(),
            inputTokens.get(),
            outputTokens.get(),
            totalTokens.get(),
            turnDurationNanos.get(),
            modelDurationNanos.get(),
            toolDurationNanos.get(),
            exporterFailures.get()
        );
    }
}
