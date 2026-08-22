# MCP Client and Tool Integration

## Scope

`agent-mcp` lets an Agent consume Tools exposed by an MCP server. It supports
the current MCP 2026-07-28 protocol and legacy initialization-based servers
over stdio:

- stateless 2026 requests with protocol version, client identity and client
  capabilities on every request;
- `server/discover`, `resultType` validation, discovery and Tool-list cache
  hints;
- automatic stdio era detection with fallback to the 2025-11-25
  `initialize`/`notifications/initialized` lifecycle;
- fully paginated `tools/list` with duplicate and size limits;
- asynchronous `tools/call` through `CompletableFuture`;
- 2026 multi round-trip `input_required` Tool results through an application
  supplied `McpInputHandler`;
- JSON-RPC request correlation, timeout and cancellation notification;
- bounded server `stderr` diagnostics and deterministic subprocess shutdown;
- conversion of discovered MCP Tools into normal Agent SDK `Tool` instances.

This is intentionally a Tool integration. It does not add a Graph, workflow,
or parallel execution runtime. MCP Tools use the existing Agent Loop,
`ToolExecutor`, interceptors, error policy and parallel Tool-call behavior.

## Why the official Java SDK is not a dependency

The official SDK was evaluated first and remains a reference for API
semantics. However, its current
[`io.modelcontextprotocol.sdk:mcp:2.0.0`](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp/2.0.0)
build sets Java 17 as its compiler release in its
[`pom.xml`](https://github.com/modelcontextprotocol/java-sdk/blob/main/pom.xml),
while this project promises Java 8 compatibility. That release also targets
the initialization-based 2025-11-25 protocol rather than the stateless
2026-07-28 revision. Linking it would both raise the runtime baseline and not
provide the new wire protocol.

The available Java 8 backport is an old fork of the pre-1.0 SDK and is not
published in Maven Central, so it is not used as a production dependency.
Instead, `agent-mcp` implements a narrow Java 8 client directly from the
[official MCP 2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28),
and isolates it behind `McpClient`. A future optional Java 17 module can provide
an official-SDK-backed `McpClient` without changing `agent-core` or Agent code.

## Maven dependency

```xml
<dependency>
    <groupId>io.github.gitsilence</groupId>
    <artifactId>agent-mcp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Basic usage

The command is passed directly to `ProcessBuilder`; no shell is inserted.
Arguments therefore remain separate and are not subject to shell expansion.

```java
import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.mcp.McpToolSet;
import io.github.gitsilence.agent.mcp.StdioMcpClient;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.runtime.Futures;

import java.nio.file.Paths;
import java.time.Duration;

ChatModel model = createModel();

StdioMcpClient client = StdioMcpClient.builder("npx")
    .arguments(
        "-y",
        "@modelcontextprotocol/server-filesystem",
        Paths.get(".").toAbsolutePath().normalize().toString()
    )
    .requestTimeout(Duration.ofSeconds(30))
    .build();

try (McpToolSet filesystem = Futures.join(
        McpToolSet.discover(client, "filesystem"))) {
    Agent agent = Agent.builder()
        .name("assistant")
        .description("An assistant with workspace access")
        .model(model)
        .tools(filesystem.getTools())
        .build();

    System.out.println(agent.run("List the files in this directory")
        .getOutput());
}
```

`McpToolSet.discover` prepares the connection, follows every `tools/list` page,
and returns an immutable snapshot. For a modern server, preparation means
`server/discover`; no wire-level `initialize` session exists. For a legacy
server, it means the old initialization handshake. Closing the set closes the
MCP client and its child process. The Agent must finish using those Tools before
the set is closed. The overload accepting `closeClient=false` supports a
separately owned client.

## Protocol selection and cache hints

`AUTO` is the default. On stdio it probes with a modern `server/discover`
request. A non-modern error or timeout causes the server process to be restarted
and opened using the 2025-11-25 handshake. Recognized modern protocol errors
are surfaced instead of being incorrectly treated as a legacy server.

```java
import io.github.gitsilence.agent.mcp.McpProtocolMode;

StdioMcpClient modernOnly = StdioMcpClient.builder("my-mcp-server")
    .protocolMode(McpProtocolMode.STATELESS)
    .clientCapabilities("{\"elicitation\":{\"form\":{}}}")
    .build();

StdioMcpClient legacyOnly = StdioMcpClient.builder("old-mcp-server")
    .protocolMode(McpProtocolMode.LEGACY)
    .build();
```

Use an explicit mode when starting the process twice during auto-detection is
undesirable or when the server era is already known. `McpInitializeResult`
exposes the selected era, protocol versions, server capabilities and discovery
cache hint. `McpToolSet.getCatalog()` exposes the aggregated `tools/list`
`ttlMs` and `cacheScope`; the snapshot is not silently refreshed.

## Multi round-trip Tool input

MCP 2026 replaces server-initiated requests with an `input_required` result.
The client passes its opaque `inputRequests` and `requestState` to an async
handler, then retries the original Tool call with a new JSON-RPC id:

```java
StdioMcpClient client = StdioMcpClient.builder("my-mcp-server")
    .clientCapabilities("{\"elicitation\":{\"form\":{}}}")
    .inputHandler(required -> {
        // Render required.getInputRequestsJson() in the host application's UI.
        // Keys in this JSON response must match the inputRequests keys.
        return CompletableFuture.completedFuture(
            "{\"confirmation\":{\"action\":\"accept\"}}"
        );
    })
    .maxInputRounds(4)
    .build();
```

The SDK intentionally does not display UI or ask the LLM for approval by
itself. Without a handler it fails with `MCP_INPUT_REQUIRED`, including a
bounded diagnostic of the pending requests. Handler failures and excessive
rounds have separate stable error codes. Cancellation propagates to either the
active MCP request or the handler future.

## Tool names

Every server must be assigned an explicit local namespace. A remote `read_file`
Tool under namespace `filesystem` is normally exposed as
`filesystem__read_file`.

MCP allows names, such as `files.read`, that the SDK's provider-neutral Tool
contract cannot send to every model API. Such names are sanitized and receive
a deterministic SHA-256 suffix. The exact remote name is retained in Tool
metadata and `McpToolSet.getLocalToRemoteNames()`.

This namespacing also avoids collisions when several MCP servers are attached
to one Agent:

```java
Agent agent = Agent.builder()
    .name("assistant")
    .description("Uses several remote Tool sets")
    .model(model)
    .tools(filesystem.getTools())
    .tools(database.getTools())
    .build();
```

## Results and errors

MCP text and embedded text resources become ordinary model-facing Tool text.
`structuredContent` is appended as JSON. Resource links become compact URI
descriptions.

Image, audio, blob and unknown content is not copied into model context as
base64. The exact `tools/call` result JSON is written once through
`ToolOutputStore`, and the returned `ToolResult` carries a temporary-file
reference and path. Long textual output is handled by the Agent's normal
`ToolResultPolicy`, which preserves the complete output before creating a
head/tail preview. Existing references prevent a second recursive spill.

An MCP `isError=true` response becomes a structured `MCP_TOOL_ERROR` result so
the model can correct its arguments. Transport, timeout, lifecycle and JSON-RPC
failures become stable `ToolFailureException` codes and follow the Agent's
configured `ToolErrorPolicy`.

Set the MCP request timeout below the Agent Tool timeout when the model should
receive the more specific MCP diagnostic:

```java
StdioMcpClient client = StdioMcpClient.builder("my-mcp-server")
    .requestTimeout(Duration.ofSeconds(20))
    .build();

try (McpToolSet remote = Futures.join(
        McpToolSet.discover(client, "remote"))) {
    Agent agent = Agent.builder()
        .name("assistant")
        .description("MCP assistant")
        .model(model)
        .tools(remote.getTools())
        .toolTimeout(Duration.ofSeconds(30))
        .build();
    // Run the Agent before the MCP Tool set is closed.
}
```

## Security and lifecycle guidance

- Starting an MCP stdio server executes a local program. Use trusted commands,
  pin packages where appropriate, and avoid accepting command lines from the
  model.
- Server Tool descriptions and annotations are untrusted input. Use a
  `ToolInterceptor` to enforce allowlists or human approval for sensitive
  operations.
- The transport captures `stderr` only as a bounded diagnostic tail; stderr by
  itself is not treated as failure, as required by the MCP transport spec.
- A stdout message is bounded before JSON parsing. Tool discovery also has
  page and total-Tool limits.
- 2026 Tool-list notifications require a subscription, which is not yet
  implemented. Cache hints are exposed but not used to hot-mutate an already
  built Agent. Rediscover a new `McpToolSet` and build a new Agent when needed.
- Legacy experimental Tools declaring `execution.taskSupport=required` fail
  Tool-set discovery explicitly. The 2026 Tasks extension is not advertised.

## Deliberately deferred

- Streamable HTTP and legacy HTTP+SSE transports;
- authorization and remote-session resumption;
- resources and prompts;
- first-class roots, sampling and elicitation APIs beyond the generic
  `McpInputHandler` bridge;
- subscriptions and the MCP Tasks extension;
- MCP server mode;
- live mutation of an Agent's immutable Tool registry.

These features can be added behind `McpClient` or as separate capability APIs
without changing the fixed Agent Loop.
