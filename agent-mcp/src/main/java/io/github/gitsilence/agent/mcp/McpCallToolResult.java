package io.github.gitsilence.agent.mcp;

import java.util.Objects;

/** Normalized MCP Tool result plus the exact JSON needed for lossless spill. */
public final class McpCallToolResult {

    private final boolean error;
    private final String modelContent;
    private final String rawResultJson;
    private final String structuredContentJson;
    private final boolean omittedFromModelContent;

    public McpCallToolResult(boolean error,
                             String modelContent,
                             String rawResultJson,
                             String structuredContentJson,
                             boolean omittedFromModelContent) {
        this.error = error;
        this.modelContent = Objects.requireNonNull(modelContent, "modelContent");
        this.rawResultJson = Objects.requireNonNull(rawResultJson, "rawResultJson");
        this.structuredContentJson = structuredContentJson == null
            ? "" : structuredContentJson;
        this.omittedFromModelContent = omittedFromModelContent;
    }

    public boolean isError() { return error; }
    public String getModelContent() { return modelContent; }
    public String getRawResultJson() { return rawResultJson; }
    public String getStructuredContentJson() { return structuredContentJson; }
    public boolean isOmittedFromModelContent() { return omittedFromModelContent; }
}
