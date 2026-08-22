package io.github.gitsilence.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolResult {

    private final boolean error;
    private final String content;
    private final Map<String, Object> metadata;
    private final ToolErrorInfo errorInfo;
    private final List<ToolOutputReference> outputReferences;

    private ToolResult(boolean error,
                       String content,
                       Map<String, Object> metadata,
                       ToolErrorInfo errorInfo,
                       List<ToolOutputReference> outputReferences) {
        this.error = error;
        this.content = Objects.requireNonNull(content, "content");
        this.metadata = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(metadata)
        );
        this.errorInfo = errorInfo;
        this.outputReferences = Collections.unmodifiableList(
            new ArrayList<ToolOutputReference>(outputReferences)
        );
    }

    public static ToolResult success(String content) {
        return new ToolResult(
            false, content, Collections.<String, Object>emptyMap(), null,
            Collections.<ToolOutputReference>emptyList()
        );
    }

    public static ToolResult failure(String content) {
        return new ToolResult(
            true, content, Collections.<String, Object>emptyMap(), null,
            Collections.<ToolOutputReference>emptyList()
        );
    }

    public static ToolResult failure(ToolErrorInfo errorInfo) {
        Objects.requireNonNull(errorInfo, "errorInfo");
        return new ToolResult(
            true,
            errorInfo.toModelMessage(),
            Collections.<String, Object>emptyMap(),
            errorInfo,
            Collections.<ToolOutputReference>emptyList()
        );
    }

    public static ToolResult failure(String content, ToolErrorInfo errorInfo) {
        return new ToolResult(
            true,
            content,
            Collections.<String, Object>emptyMap(),
            Objects.requireNonNull(errorInfo, "errorInfo"),
            Collections.<ToolOutputReference>emptyList()
        );
    }

    public ToolResult withMetadata(String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(metadata);
        copy.put(name, value);
        return new ToolResult(error, content, copy, errorInfo, outputReferences);
    }

    public ToolResult withContent(String content) {
        return new ToolResult(error, content, metadata, errorInfo, outputReferences);
    }

    public ToolResult withOutputReference(ToolOutputReference reference) {
        List<ToolOutputReference> copy =
            new ArrayList<ToolOutputReference>(outputReferences);
        copy.add(Objects.requireNonNull(reference, "reference"));
        return new ToolResult(error, content, metadata, errorInfo, copy);
    }

    public boolean isError() {
        return error;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public ToolErrorInfo getErrorInfo() {
        return errorInfo;
    }

    public List<ToolOutputReference> getOutputReferences() {
        return outputReferences;
    }
}
