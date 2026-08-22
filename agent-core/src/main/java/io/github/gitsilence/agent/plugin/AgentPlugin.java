package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.runtime.AgentEvent;

import java.util.Collections;
import java.util.List;

/**
 * A build-time bundle of runtime extension points.
 * Plugins are immutable after an Agent is built.
 */
public interface AgentPlugin {

    default String name() {
        return getClass().getName();
    }

    /** Receives read-only lifecycle facts and must not control execution. */
    default void onEvent(AgentEvent event) {
    }

    default List<ModelInterceptor> modelInterceptors() {
        return Collections.emptyList();
    }

    default List<ToolInterceptor> toolInterceptors() {
        return Collections.emptyList();
    }
}
