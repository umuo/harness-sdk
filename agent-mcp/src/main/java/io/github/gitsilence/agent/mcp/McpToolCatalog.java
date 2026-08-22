package io.github.gitsilence.agent.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Discovered Tools plus 2026 MCP cache hints aggregated across list pages. */
public final class McpToolCatalog {

    private final List<McpToolDefinition> tools;
    private final long ttlMillis;
    private final String cacheScope;
    private final long fetchedAtEpochMillis;

    public McpToolCatalog(List<McpToolDefinition> tools,
                          long ttlMillis,
                          String cacheScope,
                          long fetchedAtEpochMillis) {
        this.tools = Collections.unmodifiableList(
            new ArrayList<McpToolDefinition>(
                Objects.requireNonNull(tools, "tools")
            )
        );
        if (ttlMillis < -1) {
            throw new IllegalArgumentException("ttlMillis must be -1 or non-negative");
        }
        Objects.requireNonNull(cacheScope, "cacheScope");
        if (!cacheScope.isEmpty()
                && !"public".equals(cacheScope)
                && !"private".equals(cacheScope)) {
            throw new IllegalArgumentException(
                "cacheScope must be public, private, or empty"
            );
        }
        this.ttlMillis = ttlMillis;
        this.cacheScope = cacheScope;
        this.fetchedAtEpochMillis = fetchedAtEpochMillis;
    }

    public static McpToolCatalog uncached(List<McpToolDefinition> tools) {
        return new McpToolCatalog(tools, -1, "", System.currentTimeMillis());
    }

    public List<McpToolDefinition> getTools() { return tools; }
    public long getTtlMillis() { return ttlMillis; }
    public String getCacheScope() { return cacheScope; }
    public long getFetchedAtEpochMillis() { return fetchedAtEpochMillis; }

    public boolean hasCacheHint() {
        return ttlMillis >= 0;
    }

    public boolean isFresh(long nowEpochMillis) {
        if (!hasCacheHint() || ttlMillis == 0) return false;
        long age = Math.max(0, nowEpochMillis - fetchedAtEpochMillis);
        return age < ttlMillis;
    }
}
