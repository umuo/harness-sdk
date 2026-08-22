package io.github.gitsilence.agent.observability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** A bounded, thread-safe exporter intended for tests and local diagnostics. */
public final class InMemoryTraceExporter implements AgentTraceExporter {

    private final int capacity;
    private final Deque<AgentTrace> traces = new ArrayDeque<AgentTrace>();
    private long droppedTraceCount;

    public InMemoryTraceExporter() {
        this(1_000);
    }

    public InMemoryTraceExporter(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void export(AgentTrace trace) {
        Objects.requireNonNull(trace, "trace");
        if (traces.size() == capacity) {
            traces.removeFirst();
            droppedTraceCount++;
        }
        traces.addLast(trace);
    }

    public synchronized List<AgentTrace> getTraces() {
        return Collections.unmodifiableList(new ArrayList<AgentTrace>(traces));
    }

    public synchronized long getDroppedTraceCount() {
        return droppedTraceCount;
    }

    public synchronized void clear() {
        traces.clear();
        droppedTraceCount = 0L;
    }
}
