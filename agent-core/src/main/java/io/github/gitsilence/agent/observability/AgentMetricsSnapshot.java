package io.github.gitsilence.agent.observability;

/** An immutable process-local metrics snapshot collected from completed Turns. */
public final class AgentMetricsSnapshot {

    private final long turnsStarted;
    private final long turnsCompleted;
    private final long turnsStopped;
    private final long turnsFailed;
    private final long turnsCancelled;
    private final long activeTurns;
    private final long steps;
    private final long modelCalls;
    private final long toolCalls;
    private final long toolErrors;
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final long turnDurationNanos;
    private final long modelDurationNanos;
    private final long toolDurationNanos;
    private final long exporterFailures;

    AgentMetricsSnapshot(long turnsStarted,
                         long turnsCompleted,
                         long turnsStopped,
                         long turnsFailed,
                         long turnsCancelled,
                         long activeTurns,
                         long steps,
                         long modelCalls,
                         long toolCalls,
                         long toolErrors,
                         long inputTokens,
                         long outputTokens,
                         long totalTokens,
                         long turnDurationNanos,
                         long modelDurationNanos,
                         long toolDurationNanos,
                         long exporterFailures) {
        this.turnsStarted = turnsStarted;
        this.turnsCompleted = turnsCompleted;
        this.turnsStopped = turnsStopped;
        this.turnsFailed = turnsFailed;
        this.turnsCancelled = turnsCancelled;
        this.activeTurns = activeTurns;
        this.steps = steps;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.toolErrors = toolErrors;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.turnDurationNanos = turnDurationNanos;
        this.modelDurationNanos = modelDurationNanos;
        this.toolDurationNanos = toolDurationNanos;
        this.exporterFailures = exporterFailures;
    }

    public long getTurnsStarted() { return turnsStarted; }
    public long getTurnsCompleted() { return turnsCompleted; }
    public long getTurnsStopped() { return turnsStopped; }
    public long getTurnsFailed() { return turnsFailed; }
    public long getTurnsCancelled() { return turnsCancelled; }
    public long getActiveTurns() { return activeTurns; }
    public long getSteps() { return steps; }
    public long getModelCalls() { return modelCalls; }
    public long getToolCalls() { return toolCalls; }
    public long getToolErrors() { return toolErrors; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public long getTurnDurationNanos() { return turnDurationNanos; }
    public long getModelDurationNanos() { return modelDurationNanos; }
    public long getToolDurationNanos() { return toolDurationNanos; }
    public long getExporterFailures() { return exporterFailures; }
}
