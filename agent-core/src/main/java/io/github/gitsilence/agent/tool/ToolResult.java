package io.github.gitsilence.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ToolResult {

    private final boolean error;
    private final String content;
    private final Map<String, Object> metadata;

    private ToolResult(boolean error, String content, Map<String, Object> metadata) {
        this.error = error;
        this.content = Objects.requireNonNull(content, "content");
        this.metadata = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(metadata)
        );
    }

    public static ToolResult success(String content) {
        return new ToolResult(false, content, Collections.<String, Object>emptyMap());
    }

    public static ToolResult failure(String content) {
        return new ToolResult(true, content, Collections.<String, Object>emptyMap());
    }

    public ToolResult withMetadata(String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(metadata);
        copy.put(name, value);
        return new ToolResult(error, content, copy);
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
}
