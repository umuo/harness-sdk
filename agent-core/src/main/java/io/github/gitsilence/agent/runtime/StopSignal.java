package io.github.gitsilence.agent.runtime;

import java.util.Objects;

public final class StopSignal {

    private final boolean completed;
    private final String output;
    private final String reason;

    private StopSignal(boolean completed, String output, String reason) {
        this.completed = completed;
        this.output = output;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public static StopSignal complete(String output, String reason) {
        return new StopSignal(true, Objects.requireNonNull(output, "output"), reason);
    }

    public static StopSignal stop(String reason) {
        return new StopSignal(false, null, reason);
    }

    public boolean isCompleted() {
        return completed;
    }

    public String getOutput() {
        return output;
    }

    public String getReason() {
        return reason;
    }
}
