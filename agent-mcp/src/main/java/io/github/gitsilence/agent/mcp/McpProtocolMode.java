package io.github.gitsilence.agent.mcp;

/** Selects stateless 2026 MCP, legacy stateful MCP, or stdio auto-detection. */
public enum McpProtocolMode {
    AUTO,
    STATELESS,
    LEGACY
}
