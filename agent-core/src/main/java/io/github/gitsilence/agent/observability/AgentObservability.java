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
 * 从 Agent 生命周期事件组装与导出器无关的 Trace 和进程内指标。
 *
 * <p>该实现是线程安全的，同一实例可由父子 Agent 共享，以便通过 traceId 关联独立
 * Turn。它是观察型 Plugin，组装或导出失败都不能改变 Agent 执行结果。</p>
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

    /** 完全关闭 Trace 组装、指标、日志和导出。 */
    public static AgentObservability disabled() {
        return builder().off().build();
    }

    /** 通过 java.util.logging 为每个结束的 Turn 写一条有界 JSON Trace。 */
    public static AgentObservability logging() {
        return builder().logging().build();
    }

    public static AgentObservability logging(Logger logger) {
        return builder().logging(logger).build();
    }

    /**
     * 异步发送 Trace，并为平台调试器捕获有界的节点输入/输出；如需禁用内容捕获，
     * 请使用 Builder 显式配置。
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
            // 每个活动 Turn 独占一个 Assembler；终态事件到达后立即移除。
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
            // 可观测性失败只累计指标，绝不能改变 Agent 执行语义。
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
         * 把平台导出器的生命周期所有权交给生成的观测插件；关闭插件时会排空并关闭
         * 导出器。
         */
        public Builder platform(PlatformTraceExporter exporter) {
            this.exporter = Objects.requireNonNull(exporter, "exporter");
            this.mode = AgentObservabilityMode.PLATFORM;
            this.ownsExporter = true;
            return this;
        }

        /**
         * 显式开启有界的提示词、响应、Tool 参数和结果捕获。默认关闭，因为这些内容
         * 可能包含密钥、个人数据或业务敏感信息。
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

        /** 为每个导出的 Turn Trace 添加资源级属性。 */
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
