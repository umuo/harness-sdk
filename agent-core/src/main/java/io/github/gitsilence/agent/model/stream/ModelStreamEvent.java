package io.github.gitsilence.agent.model.stream;

import io.github.gitsilence.agent.model.Usage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModelStreamEvent {

    private final ModelStreamEventType type;
    private final int index;
    private final String toolCallId;
    private final String toolName;
    private final String delta;
    private final Usage usage;
    private final Map<String, Object> metadata;

    private ModelStreamEvent(ModelStreamEventType type,
                             int index,
                             String toolCallId,
                             String toolName,
                             String delta,
                             Usage usage,
                             Map<String, Object> metadata) {
        this.type = type;
        this.index = index;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.delta = delta;
        this.usage = usage;
        this.metadata = metadata == null
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    public static ModelStreamEvent responseStarted(Map<String, Object> metadata) {
        return new ModelStreamEvent(
            ModelStreamEventType.RESPONSE_STARTED, -1, null, null, null, null, metadata
        );
    }

    public static ModelStreamEvent textDelta(String delta) {
        return new ModelStreamEvent(
            ModelStreamEventType.TEXT_DELTA, -1, null, null, delta, null, null
        );
    }

    public static ModelStreamEvent toolCallStarted(int index,
                                                   String toolCallId,
                                                   String toolName) {
        return new ModelStreamEvent(
            ModelStreamEventType.TOOL_CALL_STARTED,
            index,
            toolCallId,
            toolName,
            null,
            null,
            null
        );
    }

    public static ModelStreamEvent toolArgumentsDelta(int index,
                                                      String toolCallId,
                                                      String toolName,
                                                      String delta) {
        return new ModelStreamEvent(
            ModelStreamEventType.TOOL_ARGUMENTS_DELTA,
            index,
            toolCallId,
            toolName,
            delta,
            null,
            null
        );
    }

    public static ModelStreamEvent usage(Usage usage) {
        return new ModelStreamEvent(
            ModelStreamEventType.USAGE, -1, null, null, null, usage, null
        );
    }

    public ModelStreamEventType getType() { return type; }
    public int getIndex() { return index; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public String getDelta() { return delta; }
    public Usage getUsage() { return usage; }
    public Map<String, Object> getMetadata() { return metadata; }
}
