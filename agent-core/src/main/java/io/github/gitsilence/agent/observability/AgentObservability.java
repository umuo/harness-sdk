package io.github.gitsilence.agent.observability;

import io.github.gitsilence.agent.plugin.AgentPlugin;
import io.github.gitsilence.agent.runtime.AgentEvent;
import io.github.gitsilence.agent.runtime.AgentEventType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Builds exporter-neutral traces and process-local metrics from Agent events.
 * One instance may safely be shared by parent and child Agents.
 */
public final class AgentObservability implements AgentPlugin {

    private final AgentTraceExporter exporter;
    private final boolean captureContent;
    private final int maxCapturedContentCharacters;
    private final Map<String, Object> attributes;
    private final ConcurrentMap<String, AgentTraceAssembler> active =
        new ConcurrentHashMap<String, AgentTraceAssembler>();
    private final AgentMetrics metrics = new AgentMetrics();

    private AgentObservability(Builder builder) {
        this.exporter = builder.exporter;
        this.captureContent = builder.captureContent;
        this.maxCapturedContentCharacters =
            builder.maxCapturedContentCharacters;
        this.attributes = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(builder.attributes)
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "agent-observability";
    }

    @Override
    public void onEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.getType() == AgentEventType.TURN_STARTED) {
            AgentTraceAssembler created = new AgentTraceAssembler(
                event,
                captureContent,
                maxCapturedContentCharacters,
                attributes
            );
            AgentTraceAssembler previous = active.putIfAbsent(
                event.getTurnId(), created
            );
            if (previous == null) {
                metrics.incrementTurnsStarted();
            }
        }

        AgentTraceAssembler assembler = active.get(event.getTurnId());
        if (assembler == null) return;
        AgentTrace completed = assembler.accept(event);
        if (completed == null
                || !active.remove(event.getTurnId(), assembler)) {
            return;
        }

        metrics.record(completed);
        try {
            exporter.export(completed);
        } catch (Throwable ignored) {
            // Observability must never change Agent execution semantics.
            metrics.incrementExporterFailures();
        }
    }

    public AgentMetricsSnapshot metrics() {
        return metrics.snapshot(active.size());
    }

    public AgentMetricsSnapshot getMetrics() {
        return metrics();
    }

    public int getActiveTurnCount() {
        return active.size();
    }

    public static final class Builder {
        private AgentTraceExporter exporter = AgentTraceExporter.noop();
        private boolean captureContent;
        private int maxCapturedContentCharacters = 4_096;
        private final Map<String, Object> attributes =
            new LinkedHashMap<String, Object>();

        public Builder exporter(AgentTraceExporter exporter) {
            this.exporter = Objects.requireNonNull(exporter, "exporter");
            return this;
        }

        /**
         * Opts into bounded prompt, response, Tool argument, and Tool result
         * capture. It is disabled by default because these values may contain
         * secrets or personal data.
         */
        public Builder captureContent(boolean captureContent) {
            this.captureContent = captureContent;
            return this;
        }

        public Builder maxCapturedContentCharacters(int value) {
            if (value < 128) {
                throw new IllegalArgumentException(
                    "maxCapturedContentCharacters must be at least 128"
                );
            }
            this.maxCapturedContentCharacters = value;
            return this;
        }

        /** Adds a resource-style attribute to every exported Turn trace. */
        public Builder attribute(String name, Object value) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException(
                    "attribute name must not be blank"
                );
            }
            attributes.put(name, Objects.requireNonNull(value, "value"));
            return this;
        }

        public AgentObservability build() {
            return new AgentObservability(this);
        }
    }
}
