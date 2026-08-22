package io.github.gitsilence.agent.mcp;

import java.util.Objects;

/** Opaque 2026 MCP input_required result passed to an application handler. */
public final class McpInputRequired {

    private final String toolName;
    private final int round;
    private final String inputRequestsJson;
    private final String requestState;
    private final String rawResultJson;

    McpInputRequired(String toolName,
                     int round,
                     String inputRequestsJson,
                     String requestState,
                     String rawResultJson) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.round = round;
        this.inputRequestsJson = inputRequestsJson == null
            ? "{}" : inputRequestsJson;
        this.requestState = requestState == null ? "" : requestState;
        this.rawResultJson = Objects.requireNonNull(rawResultJson, "rawResultJson");
    }

    public String getToolName() { return toolName; }
    public int getRound() { return round; }
    public String getInputRequestsJson() { return inputRequestsJson; }
    public String getRequestState() { return requestState; }
    public String getRawResultJson() { return rawResultJson; }
}
