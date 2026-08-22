package io.github.gitsilence.agent.state;

import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.runtime.ExecutionStatus;
import io.github.gitsilence.agent.todo.TodoStore;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AgentState {

    private final String runId;
    private final String agentName;
    private final List<ChatMessage> messages;
    private final List<ToolExecutionRecord> toolResults =
        new ArrayList<ToolExecutionRecord>();
    private final TodoStore todos = new TodoStore();
    private final Map<String, Object> metadata;
    private final Map<String, Object> variables;
    private ExecutionStatus status = ExecutionStatus.CREATED;
    private int step;
    private String finalOutput;
    private String stopReason;
    private AgentError error;
    private Instant startedAt;
    private Instant endedAt;

    public AgentState(String runId,
                      String agentName,
                      List<ChatMessage> initialMessages,
                      Map<String, Object> metadata,
                      Map<String, Object> variables) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.messages = new ArrayList<ChatMessage>(initialMessages);
        this.metadata = new LinkedHashMap<String, Object>(metadata);
        this.variables = new LinkedHashMap<String, Object>(variables);
    }

    public synchronized void start() {
        requireStatus(ExecutionStatus.CREATED);
        status = ExecutionStatus.RUNNING;
        startedAt = Instant.now();
    }

    public synchronized void beginStep() {
        requireStatus(ExecutionStatus.RUNNING);
        step++;
    }

    public synchronized void appendMessage(ChatMessage message) {
        requireStatus(ExecutionStatus.RUNNING);
        messages.add(Objects.requireNonNull(message, "message"));
    }

    public synchronized void appendToolExecution(ToolExecutionRecord record) {
        requireStatus(ExecutionStatus.RUNNING);
        toolResults.add(Objects.requireNonNull(record, "record"));
    }

    public synchronized void complete(String output) {
        requireStatus(ExecutionStatus.RUNNING);
        status = ExecutionStatus.COMPLETED;
        finalOutput = output;
        stopReason = "MODEL_FINAL_ANSWER";
        endedAt = Instant.now();
    }

    public synchronized void complete(String output, String reason) {
        requireStatus(ExecutionStatus.RUNNING);
        status = ExecutionStatus.COMPLETED;
        finalOutput = output;
        stopReason = reason;
        endedAt = Instant.now();
    }

    public synchronized void stop(String reason) {
        requireStatus(ExecutionStatus.RUNNING);
        status = ExecutionStatus.STOPPED;
        stopReason = reason;
        endedAt = Instant.now();
    }

    public synchronized void fail(Throwable throwable) {
        if (status == ExecutionStatus.COMPLETED
            || status == ExecutionStatus.STOPPED
            || status == ExecutionStatus.CANCELLED) {
            return;
        }
        status = ExecutionStatus.FAILED;
        error = AgentError.from(throwable);
        stopReason = "ERROR";
        endedAt = Instant.now();
    }

    public synchronized void cancel() {
        if (status == ExecutionStatus.RUNNING || status == ExecutionStatus.CREATED) {
            status = ExecutionStatus.CANCELLED;
            stopReason = "CANCELLED";
            endedAt = Instant.now();
        }
    }

    public synchronized List<ChatMessage> messagesSnapshot() {
        return new ArrayList<ChatMessage>(messages);
    }

    public synchronized Map<String, Object> metadataSnapshot() {
        return new LinkedHashMap<String, Object>(metadata);
    }

    public synchronized Optional<Object> variable(String name) {
        return Optional.ofNullable(variables.get(name));
    }

    public synchronized void putVariable(String name, Object value) {
        variables.put(Objects.requireNonNull(name, "name"), value);
    }

    public TodoStore todos() {
        return todos;
    }

    public synchronized int getStep() {
        return step;
    }

    public synchronized ExecutionStatus getStatus() {
        return status;
    }

    public String getRunId() {
        return runId;
    }

    public String getTurnId() {
        return runId;
    }

    public synchronized AgentStateSnapshot snapshot() {
        return new AgentStateSnapshot(
            runId,
            agentName,
            messages,
            toolResults,
            todos.list(),
            metadata,
            variables,
            status,
            step,
            finalOutput,
            stopReason,
            error,
            startedAt,
            endedAt
        );
    }

    private void requireStatus(ExecutionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "Expected state " + expected + " but was " + status
            );
        }
    }
}
