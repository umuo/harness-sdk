package io.github.gitsilence.agent.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Negotiated legacy initialization or stateless server discovery metadata. */
public final class McpInitializeResult {

    private final String protocolVersion;
    private final McpServerInfo serverInfo;
    private final String capabilitiesJson;
    private final String instructions;
    private final boolean toolsSupported;
    private final boolean stateless;
    private final List<String> supportedProtocolVersions;
    private final long discoveryTtlMillis;
    private final String cacheScope;

    McpInitializeResult(String protocolVersion,
                        McpServerInfo serverInfo,
                        String capabilitiesJson,
                        String instructions,
                        boolean toolsSupported) {
        this(
            protocolVersion,
            serverInfo,
            capabilitiesJson,
            instructions,
            toolsSupported,
            false,
            Collections.singletonList(protocolVersion),
            -1,
            ""
        );
    }

    McpInitializeResult(String protocolVersion,
                        McpServerInfo serverInfo,
                        String capabilitiesJson,
                        String instructions,
                        boolean toolsSupported,
                        boolean stateless,
                        List<String> supportedProtocolVersions,
                        long discoveryTtlMillis,
                        String cacheScope) {
        this.protocolVersion = Objects.requireNonNull(
            protocolVersion, "protocolVersion"
        );
        this.serverInfo = Objects.requireNonNull(serverInfo, "serverInfo");
        this.capabilitiesJson = Objects.requireNonNull(
            capabilitiesJson, "capabilitiesJson"
        );
        this.instructions = instructions == null ? "" : instructions;
        this.toolsSupported = toolsSupported;
        this.stateless = stateless;
        this.supportedProtocolVersions = Collections.unmodifiableList(
            new ArrayList<String>(Objects.requireNonNull(
                supportedProtocolVersions, "supportedProtocolVersions"
            ))
        );
        this.discoveryTtlMillis = discoveryTtlMillis;
        this.cacheScope = cacheScope == null ? "" : cacheScope;
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

    public boolean isStateless() { return stateless; }
    public List<String> getSupportedProtocolVersions() {
        return supportedProtocolVersions;
    }
    public long getDiscoveryTtlMillis() { return discoveryTtlMillis; }
    public String getCacheScope() { return cacheScope; }
}
