package io.github.gitsilence.agent.mcp;

import java.util.Objects;

public final class McpServerInfo {

    private final String name;
    private final String version;
    private final String title;

    McpServerInfo(String name, String version, String title) {
        this.name = requireText(name, "server name");
        this.version = version == null ? "" : version;
        this.title = title == null ? "" : title;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getTitle() {
        return title;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
