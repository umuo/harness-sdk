package io.github.gitsilence.agent.observability;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Emits one stable JSON trace document through java.util.logging. */
public final class LoggingTraceExporter implements AgentTraceExporter {

    public static final String DEFAULT_LOGGER_NAME =
        "io.github.gitsilence.agent.observability";

    private final Logger logger;
    private final Level level;
    private final AgentTraceJsonCodec codec = new AgentTraceJsonCodec();

    public LoggingTraceExporter() {
        this(Logger.getLogger(DEFAULT_LOGGER_NAME), Level.INFO);
    }

    public LoggingTraceExporter(Logger logger) {
        this(logger, Level.INFO);
    }

    public LoggingTraceExporter(Logger logger, Level level) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void export(AgentTrace trace) {
        if (logger.isLoggable(level)) {
            logger.log(level, codec.toJson(trace));
        }
    }
}
