package io.github.gitsilence.agent.state;

import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.runtime.ExecutionStatus;
import io.github.gitsilence.agent.todo.Todo;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentStateSnapshot {

    private final String runId;
    private final String agentName;
    private final List<ChatMessage> messages;
    private final List<ToolExecutionRecord> toolResults;
    private final List<Todo> todos;
    private final Map<String, Object> metadata;
    private final Map<String, Object> variables;
    private final ExecutionStatus status;
    private final int step;
    private final String finalOutput;
    private final String stopReason;
    private final AgentError error;
    private final Instant startedAt;
    private final Instant endedAt;

    AgentStateSnapshot(String runId,
                       String agentName,
                       List<ChatMessage> messages,
                       List<ToolExecutionRecord> toolResults,
                       List<Todo> todos,
                       Map<String, Object> metadata,
                       Map<String, Object> variables,
                       ExecutionStatus status,
                       int step,
                       String finalOutput,
                       String stopReason,
                       AgentError error,
                       Instant startedAt,
                       Instant endedAt) {
        this.runId = runId;
        this.agentName = agentName;
        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
        this.toolResults = Collections.unmodifiableList(
            new ArrayList<ToolExecutionRecord>(toolResults)
        );
        this.todos = Collections.unmodifiableList(new ArrayList<Todo>(todos));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(variables));
        this.status = status;
        this.step = step;
        this.finalOutput = finalOutput;
        this.stopReason = stopReason;
        this.error = error;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public String getRunId() { return runId; }
    public String getTurnId() { return runId; }
    public String getAgentName() { return agentName; }
    public List<ChatMessage> getMessages() { return messages; }
    public List<ToolExecutionRecord> getToolResults() { return toolResults; }
    public List<Todo> getTodos() { return todos; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Map<String, Object> getVariables() { return variables; }
    public ExecutionStatus getStatus() { return status; }
    public int getStep() { return step; }
    public String getFinalOutput() { return finalOutput; }
    public String getStopReason() { return stopReason; }
    public AgentError getError() { return error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
}
