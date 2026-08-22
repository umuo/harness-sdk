package io.github.gitsilence.agent.model;

public final class Usage {

    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;

    public Usage(long inputTokens, long outputTokens, long totalTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }
}
