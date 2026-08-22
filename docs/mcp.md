# MCP Client and Tool Integration

## Scope

`agent-mcp` lets an Agent consume Tools exposed by an MCP server. The first
version implements the MCP 2025-11-25 client lifecycle and the stdio transport:

- lazy `initialize` followed by `notifications/initialized`;
- protocol-version validation and server capability inspection;
- fully paginated `tools/list` with duplicate and size limits;
- asynchronous `tools/call` through `CompletableFuture`;
- JSON-RPC request correlation, timeout and cancellation notification;
- bounded server `stderr` diagnostics and deterministic subprocess shutdown;
- conversion of discovered MCP Tools into normal Agent SDK `Tool` instances.

This is intentionally a Tool integration. It does not add a Graph, workflow,
or parallel execution runtime. MCP Tools use the existing Agent Loop,
`ToolExecutor`, interceptors, error policy and parallel Tool-call behavior.

## Why the official Java SDK is not a dependency

The official SDK was evaluated first and remains the reference for lifecycle
and API semantics. However, the current
[`io.modelcontextprotocol.sdk:mcp:2.0.0`](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp/2.0.0)
build sets Java 17 as its compiler release in its
[`pom.xml`](https://github.com/modelcontextprotocol/java-sdk/blob/main/pom.xml),
while this project promises Java 8 compatibility. Linking it would raise the
runtime baseline for the whole SDK.

The available Java 8 backport is an old fork of the pre-1.0 SDK and is not
published in Maven Central, so it is not used as a production dependency.
Instead, `agent-mcp` implements a narrow Java 8 client directly from the
[official MCP specification](https://modelcontextprotocol.io/specification/2025-11-25),
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

`McpToolSet.discover` initializes the server, follows every `tools/list` page,
and returns an immutable snapshot. Closing the set closes the MCP client and
its child process. The Agent must finish using those Tools before the set is
closed. The overload accepting `closeClient=false` supports a separately owned
client.

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
- `tools/list_changed` notifications are not hot-applied to an already built
  Agent. Close and rediscover a new `McpToolSet`, then build a new Agent when a
  server changes its Tool catalog.
- Experimental Tools declaring `execution.taskSupport=required` fail Tool-set
  discovery explicitly instead of being exposed as Tools that cannot execute.

## Deliberately deferred

- Streamable HTTP and legacy HTTP+SSE transports;
- authorization and remote-session resumption;
- resources and prompts;
- roots, sampling and elicitation callbacks;
- experimental MCP task-augmented requests;
- MCP server mode;
- live mutation of an Agent's immutable Tool registry.

These features can be added behind `McpClient` or as separate capability APIs
without changing the fixed Agent Loop.
