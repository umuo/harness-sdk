package io.github.gitsilence.agent.mcp;

/** A stable MCP transport, protocol, lifecycle, or JSON-RPC failure. */
public final class McpClientException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final Integer rpcCode;
    private final String rpcData;

    public McpClientException(String code,
                              String message,
                              boolean retryable) {
        this(code, message, retryable, null, null, null);
    }

    public McpClientException(String code,
                              String message,
                              boolean retryable,
                              Throwable cause) {
        this(code, message, retryable, null, null, cause);
    }

    static McpClientException rpc(String method,
                                  int rpcCode,
                                  String message,
                                  String data) {
        return new McpClientException(
            "MCP_RPC_ERROR",
            "MCP request '" + method + "' failed (JSON-RPC " + rpcCode
                + "): " + message,
            rpcCode == -32000 || rpcCode == -32001
                || rpcCode == -32602 || rpcCode == -32603,
            rpcCode,
            data,
            null
        );
    }

    private McpClientException(String code,
                               String message,
                               boolean retryable,
                               Integer rpcCode,
                               String rpcData,
                               Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.rpcCode = rpcCode;
        this.rpcData = rpcData;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getRpcCode() {
        return rpcCode;
    }

    public String getRpcData() {
        return rpcData;
    }
}
