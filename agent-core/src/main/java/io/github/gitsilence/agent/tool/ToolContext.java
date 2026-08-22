package io.github.gitsilence.agent.tool;

import io.github.gitsilence.agent.runtime.AgentRunner;
import io.github.gitsilence.agent.runtime.InvocationPath;
import io.github.gitsilence.agent.state.AgentState;
import io.github.gitsilence.agent.todo.TodoStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

public final class ToolContext {

    private final String toolCallId;
    private final AgentState state;
    private final AgentRunner runner;
    private final InvocationPath invocationPath;

    public ToolContext(String toolCallId,
                       AgentState state,
                       AgentRunner runner,
                       InvocationPath invocationPath) {
        this.toolCallId = toolCallId;
        this.state = state;
        this.runner = runner;
        this.invocationPath = invocationPath;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getRunId() {
        return state.getRunId();
    }

    public String getTurnId() {
        return state.getRunId();
    }

    public Executor getExecutor() {
        return runner.getExecutor();
    }

    public ScheduledExecutorService getScheduler() {
        return runner.getScheduler();
    }

    public Map<String, Object> getMetadata() {
        return state.metadataSnapshot();
    }

    public Optional<Object> variable(String name) {
        return state.variable(name);
    }

    public void putVariable(String name, Object value) {
        state.putVariable(name, value);
    }

    public TodoStore todos() {
        return state.todos();
    }

    public AgentRunner getRunner() {
        return runner;
    }

    public InvocationPath getInvocationPath() {
        return invocationPath;
    }
}
