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

/**
 * 单次 Agent Turn 独占的可变状态。
 *
 * <p>状态不会跨 Turn 或父子 Agent 共享。所有修改方法都进行同步，既保护并行工具
 * 访问，也保证状态机转换具有一致视图；对外暴露时使用不可变的
 * {@link AgentStateSnapshot}。</p>
 */
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
        // CREATED 只能进入一次 RUNNING，避免同一个状态被重复执行。
        requireStatus(ExecutionStatus.CREATED);
        status = ExecutionStatus.RUNNING;
        startedAt = Instant.now();
    }

    public synchronized void beginStep() {
        requireStatus(ExecutionStatus.RUNNING);
        // Step 在模型调用前递增；一次工具调用本身不会额外消耗 Step。
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
        // 已正常结束或取消的 Turn 不允许被迟到的异步异常覆盖。
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
        // 返回副本，防止模型 Provider 意外修改正在运行的消息列表。
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
