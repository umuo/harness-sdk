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
import java.util.logging.Logger;

/**
 * Builds exporter-neutral traces and process-local metrics from Agent events.
 * One instance may safely be shared by parent and child Agents.
 */
public final class AgentObservability implements AgentPlugin, AutoCloseable {

    private final AgentTraceExporter exporter;
    private final AgentObservabilityMode mode;
    private final boolean ownsExporter;
    private final boolean captureContent;
    private final int maxCapturedContentCharacters;
    private final Map<String, Object> attributes;
    private final ConcurrentMap<String, AgentTraceAssembler> active =
        new ConcurrentHashMap<String, AgentTraceAssembler>();
    private final AgentMetrics metrics = new AgentMetrics();

    private AgentObservability(Builder builder) {
        this.exporter = builder.exporter;
        this.mode = builder.mode;
        this.ownsExporter = builder.ownsExporter;
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

    /** Completely disables trace assembly, metrics, logging, and export. */
    public static AgentObservability disabled() {
        return builder().off().build();
    }

    /** Writes one bounded JSON trace record through java.util.logging. */
    public static AgentObservability logging() {
        return builder().logging().build();
    }

    public static AgentObservability logging(Logger logger) {
        return builder().logging(logger).build();
    }

    /**
     * Sends traces asynchronously and captures bounded node input/output for
     * the platform trace debugger. Use the Builder to disable content capture.
     */
    public static AgentObservability platform(String endpoint) {
        return builder().captureContent(true).platform(
            PlatformTraceExporter.builder(endpoint).build()
        ).build();
    }

    public static AgentObservability platform(String endpoint, String apiKey) {
        return builder().captureContent(true).platform(
            PlatformTraceExporter.builder(endpoint).apiKey(apiKey).build()
        ).build();
    }

    @Override
    public String name() {
        return "agent-observability";
    }

    @Override
    public boolean capturesModelExchange() {
        return mode != AgentObservabilityMode.OFF && captureContent;
    }

    @Override
    public void onEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (mode == AgentObservabilityMode.OFF) return;
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

    public AgentObservabilityMode getMode() {
        return mode;
    }

    public boolean isEnabled() {
        return mode != AgentObservabilityMode.OFF;
    }

    public AgentTraceExporter getExporter() {
        return exporter;
    }

    @Override
    public void close() {
        if (!ownsExporter || !(exporter instanceof AutoCloseable)) return;
        try {
            ((AutoCloseable) exporter).close();
        } catch (Throwable ignored) {
            metrics.incrementExporterFailures();
        }
    }

    public static final class Builder {
        private AgentTraceExporter exporter = AgentTraceExporter.noop();
        private AgentObservabilityMode mode = AgentObservabilityMode.CUSTOM;
        private boolean ownsExporter;
        private boolean captureContent;
        private int maxCapturedContentCharacters = 4_096;
        private final Map<String, Object> attributes =
            new LinkedHashMap<String, Object>();

        public Builder exporter(AgentTraceExporter exporter) {
            this.exporter = Objects.requireNonNull(exporter, "exporter");
            this.mode = AgentObservabilityMode.CUSTOM;
            this.ownsExporter = false;
            return this;
        }

        public Builder off() {
            this.exporter = AgentTraceExporter.noop();
            this.mode = AgentObservabilityMode.OFF;
            this.ownsExporter = false;
            return this;
        }

        public Builder logging() {
            return logging(new LoggingTraceExporter());
        }

        public Builder logging(Logger logger) {
            return logging(new LoggingTraceExporter(logger));
        }

        public Builder logging(LoggingTraceExporter exporter) {
            this.exporter = Objects.requireNonNull(exporter, "exporter");
            this.mode = AgentObservabilityMode.LOGGING;
            this.ownsExporter = false;
            return this;
        }

        /**
         * Transfers lifecycle ownership of the platform exporter to the
         * resulting observability plugin. Closing the plugin drains and closes
         * the exporter.
         */
        public Builder platform(PlatformTraceExporter exporter) {
            this.exporter = Objects.requireNonNull(exporter, "exporter");
            this.mode = AgentObservabilityMode.PLATFORM;
            this.ownsExporter = true;
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
