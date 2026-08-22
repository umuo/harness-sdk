package io.github.gitsilence.agent.runtime;

import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.stream.ModelStreamEvent;
import io.github.gitsilence.agent.state.AgentStateSnapshot;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;

import java.time.Instant;
import java.util.Objects;

/**
 * A normalized event emitted by one Agent invocation.
 * Event-specific payloads are nullable and identified by {@link #getType()}.
 */
public final class AgentEvent {

    private final long sequence;
    private final Instant timestamp;
    private final AgentEventType type;
    private final String runId;
    private final String agentName;
    private final int step;
    private final ModelStreamEvent modelStreamEvent;
    private final ModelRequest modelRequest;
    private final ModelResponse modelResponse;
    private final ToolCall toolCall;
    private final ToolExecutionRecord toolExecution;
    private final AgentStateSnapshot state;
    private final Throwable error;

    private AgentEvent(long sequence,
                       AgentEventType type,
                       String runId,
                       String agentName,
                       int step,
                       ModelStreamEvent modelStreamEvent,
                       ModelRequest modelRequest,
                       ModelResponse modelResponse,
                       ToolCall toolCall,
                       ToolExecutionRecord toolExecution,
                       AgentStateSnapshot state,
                       Throwable error) {
        this.sequence = sequence;
        this.timestamp = Instant.now();
        this.type = Objects.requireNonNull(type, "type");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.step = step;
        this.modelStreamEvent = modelStreamEvent;
        this.modelRequest = modelRequest;
        this.modelResponse = modelResponse;
        this.toolCall = toolCall;
        this.toolExecution = toolExecution;
        this.state = state;
        this.error = error;
    }

    static AgentEvent lifecycle(long sequence,
                                AgentEventType type,
                                String runId,
                                String agentName,
                                int step,
                                AgentStateSnapshot state,
                                Throwable error) {
        return new AgentEvent(
            sequence, type, runId, agentName, step,
            null, null, null, null, null, state, error
        );
    }

    static AgentEvent modelStarted(long sequence,
                                   String runId,
                                   String agentName,
                                   int step,
                                   ModelRequest request) {
        return new AgentEvent(
            sequence, AgentEventType.MODEL_STARTED,
            runId, agentName, step,
            null, Objects.requireNonNull(request, "request"),
            null, null, null, null, null
        );
    }

    static AgentEvent modelStream(long sequence,
                                  String runId,
                                  String agentName,
                                  int step,
                                  ModelStreamEvent event) {
        return new AgentEvent(
            sequence, AgentEventType.MODEL_STREAM_EVENT,
            runId, agentName, step,
            Objects.requireNonNull(event, "event"), null,
            null, null, null, null, null
        );
    }

    static AgentEvent modelCompleted(long sequence,
                                     String runId,
                                     String agentName,
                                     int step,
                                     ModelResponse response) {
        return new AgentEvent(
            sequence, AgentEventType.MODEL_COMPLETED,
            runId, agentName, step,
            null, null, Objects.requireNonNull(response, "response"),
            null, null, null, null
        );
    }

    static AgentEvent toolStarted(long sequence,
                                  String runId,
                                  String agentName,
                                  int step,
                                  ToolCall call) {
        return new AgentEvent(
            sequence, AgentEventType.TOOL_STARTED,
            runId, agentName, step,
            null, null, null, Objects.requireNonNull(call, "call"),
            null, null, null
        );
    }

    static AgentEvent toolCompleted(long sequence,
                                    String runId,
                                    String agentName,
                                    int step,
                                    ToolExecutionRecord execution) {
        Objects.requireNonNull(execution, "execution");
        return new AgentEvent(
            sequence, AgentEventType.TOOL_COMPLETED,
            runId, agentName, step,
            null, null, null, execution.getCall(), execution,
            null, null
        );
    }

    public long getSequence() { return sequence; }
    public Instant getTimestamp() { return timestamp; }
    public AgentEventType getType() { return type; }
    public String getRunId() { return runId; }
    public String getTurnId() { return runId; }
    public String getAgentName() { return agentName; }
    public int getStep() { return step; }
    public ModelStreamEvent getModelStreamEvent() { return modelStreamEvent; }
    public ModelRequest getModelRequest() { return modelRequest; }
    public ModelResponse getModelResponse() { return modelResponse; }
    public ToolCall getToolCall() { return toolCall; }
    public ToolExecutionRecord getToolExecution() { return toolExecution; }
    public AgentStateSnapshot getState() { return state; }
    public Throwable getError() { return error; }
}
