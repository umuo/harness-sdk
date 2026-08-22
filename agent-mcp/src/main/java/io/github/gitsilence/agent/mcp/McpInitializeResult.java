package io.github.gitsilence.agent.mcp;

import java.util.Objects;

public final class McpInitializeResult {

    private final String protocolVersion;
    private final McpServerInfo serverInfo;
    private final String capabilitiesJson;
    private final String instructions;
    private final boolean toolsSupported;

    McpInitializeResult(String protocolVersion,
                        McpServerInfo serverInfo,
                        String capabilitiesJson,
                        String instructions,
                        boolean toolsSupported) {
        this.protocolVersion = Objects.requireNonNull(
            protocolVersion, "protocolVersion"
        );
        this.serverInfo = Objects.requireNonNull(serverInfo, "serverInfo");
        this.capabilitiesJson = Objects.requireNonNull(
            capabilitiesJson, "capabilitiesJson"
        );
        this.instructions = instructions == null ? "" : instructions;
        this.toolsSupported = toolsSupported;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public McpServerInfo getServerInfo() {
        return serverInfo;
    }

    public String getCapabilitiesJson() {
        return capabilitiesJson;
    }

    public String getInstructions() {
        return instructions;
    }

    public boolean isToolsSupported() {
        return toolsSupported;
    }
}
