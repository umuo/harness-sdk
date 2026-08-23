# MCP 客户端与工具集成

## 范围

`agent-mcp` 允许 Agent 消费由 MCP 服务器暴露的工具（Tools）。它支持当前 MCP 2026-07-28 协议以及基于旧版初始化的服务器，通过标准输入输出（stdio）进行通信：

- 无状态的 2026 请求，每个请求中包含协议版本、客户端身份和客户端能力（capabilities）；
- `server/discover`、`resultType` 验证、发现（discovery）及 Tool 列表缓存提示；
- 自动检测 stdio 时代版本，并支持回退到 2025-11-25 版本的 `initialize`/`notifications/initialized` 生命周期；
- 完全分页的 `tools/list`，支持重复项和大小限制；
- 通过 `CompletableFuture` 实现异步 `tools/call`；
- 2026 版本中多轮往返的 `input_required` Tool 结果，通过应用程序提供的 `McpInputHandler` 处理；
- JSON-RPC 请求关联、超时和取消通知；
- 有界的服务器 `stderr` 诊断和确定的子进程关闭机制；
- 将发现的 MCP Tools 转换为标准的 Agent SDK `Tool` 实例。

这故意设计为一个工具集成（Tool integration）。它不会添加图（Graph）、工作流或并行执行运行时。MCP Tools 使用现有的 Agent 循环（Agent Loop）、`ToolExecutor`、拦截器、错误策略和并行 Tool 调用行为。

## 为什么不将官方 Java SDK 作为依赖项

首先对官方 SDK 进行了评估，它依然是 API 语义的参考标准。然而，它当前的 [`io.modelcontextprotocol.sdk:mcp:2.0.0`](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp/2.0.0) 构建版本在其 [`pom.xml`](https://github.com/modelcontextprotocol/java-sdk/blob/main/pom.xml) 中将 Java 17 设为了编译器目标版本，而本项目承诺兼容 Java 8。该发布版本还针对的是基于初始化的 2025-11-25 协议，而不是无状态的 2026-07-28 协议修订版。如果链接它，既会提高运行时基线，也无法提供新的网络协议。

目前可用的 Java 8 移植版是前 1.0 SDK 的一个旧分支，并且没有发布在 Maven Central 上，因此它没有被用作生产依赖。相反，`agent-mcp` 直接基于[官方 MCP 2026-07-28 规范](https://modelcontextprotocol.io/specification/2026-07-28)实现了一个精简的 Java 8 客户端，并将其隔离在 `McpClient` 之后。未来可选的 Java 17 模块可以提供基于官方 SDK 的 `McpClient`，而无需修改 `agent-core` 或 Agent 代码。

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.gitsilence</groupId>
    <artifactId>agent-mcp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 基本用法

命令直接传递给 `ProcessBuilder`；不会插入任何 shell。因此，参数保持独立，并且不受 shell 扩展的影响。

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

`McpToolSet.discover` 准备连接，跟踪每个 `tools/list` 页面，并返回一个不可变的快照。对于现代服务器，准备意味着 `server/discover`；不存在网络层的 `initialize` 会话。对于旧版服务器，这意味着旧的初始化握手。关闭集合将关闭 MCP 客户端及其子进程。Agent 必须在集合关闭之前完成对这些 Tool 的使用。接受 `closeClient=false` 的重载版本支持由外部单独管理的客户端。

## 协议选择与缓存提示

默认使用 `AUTO` 模式。在 stdio 上，它会通过现代的 `server/discover` 请求进行探测。非现代的错误或超时会导致服务器进程重启，并使用 2025-11-25 握手协议打开。可识别的现代协议错误会被抛出，而不是被错误地当作旧版服务器处理。

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

当自动检测期间进程启动两次不可取，或已经知道服务器所处时代时，请使用明确的模式。`McpInitializeResult` 公开了选定的时代版本、协议版本、服务器能力和发现缓存提示。`McpToolSet.getCatalog()` 公开了汇总后 `tools/list` 的 `ttlMs` 和 `cacheScope`；快照不会在后台默默刷新。

## 多轮 Tool 输入

MCP 2026 用 `input_required` 结果取代了服务器发起的请求。客户端将其不透明的 `inputRequests` 和 `requestState` 传递给一个异步处理程序（handler），然后使用新的 JSON-RPC id 重试原始的 Tool 调用：

```java
StdioMcpClient client = StdioMcpClient.builder("my-mcp-server")
    .clientCapabilities("{\"elicitation\":{\"form\":{}}}")
    .inputHandler(required -> {
        // 在宿主应用程序的 UI 中渲染 required.getInputRequestsJson()。
        // 这个 JSON 响应中的键必须与 inputRequests 中的键匹配。
        return CompletableFuture.completedFuture(
            "{\"confirmation\":{\"action\":\"accept\"}}"
        );
    })
    .maxInputRounds(4)
    .build();
```

SDK 故意不自行显示 UI 或向 LLM 请求批准。如果没有提供处理程序，它将以 `MCP_INPUT_REQUIRED` 失败，包含有关挂起请求的有界诊断信息。处理程序失败以及超过轮数限制会有单独的稳定错误代码。取消操作会传播到活动的 MCP 请求或处理程序的 Future 中。

## 工具名称

每个服务器必须被分配一个显式的本地命名空间。在命名空间 `filesystem` 下的远程 `read_file` Tool，通常被暴露为 `filesystem__read_file`。

MCP 允许像 `files.read` 这样的名称，但 SDK 这种独立于提供商（provider-neutral）的 Tool 契约无法将其发送到所有的模型 API。此类名称会被清理，并获得一个确定的 SHA-256 后缀。确切的远程名称被保留在 Tool 的元数据和 `McpToolSet.getLocalToRemoteNames()` 中。

当一个 Agent 附加了多个 MCP 服务器时，这种命名空间机制也可以避免冲突：

```java
Agent agent = Agent.builder()
    .name("assistant")
    .description("Uses several remote Tool sets")
    .model(model)
    .tools(filesystem.getTools())
    .tools(database.getTools())
    .build();
```

## 结果与错误

MCP 文本和内嵌的文本资源变成了面向模型的普通 Tool 文本。`structuredContent` 会被作为 JSON 追加。资源链接则变为紧凑的 URI 描述。

图像、音频、blob 数据和未知内容不会作为 base64 复制到模型上下文中。确切的 `tools/call` 结果 JSON 会被写入一次到 `ToolOutputStore` 中，返回的 `ToolResult` 包含了一个临时文件的引用和路径。长文本输出由 Agent 正常的 `ToolResultPolicy` 处理，它会在创建头部/尾部预览之前保留完整输出。现有的引用可防止二次递归溢出。

MCP 中的 `isError=true` 响应会变成一个结构化的 `MCP_TOOL_ERROR` 结果，以便模型能够修正其参数。传输、超时、生命周期和 JSON-RPC 失败会转化为稳定的 `ToolFailureException` 代码，并遵循 Agent 配置的 `ToolErrorPolicy`。

当模型需要接收更具体的 MCP 诊断信息时，应将 MCP 请求超时设置为低于 Agent Tool 的超时时间：

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
    // 在 MCP Tool 集合关闭之前运行 Agent。
}
```

## 安全与生命周期指导

- 启动一个 MCP stdio 服务器等同于执行一个本地程序。请使用受信任的命令，在合适的情况下锁定包版本，并避免接受来自模型的命令行参数。
- 服务器提供的 Tool 描述和注解是不可信的输入。使用 `ToolInterceptor` 对敏感操作执行白名单或人工审批。
- 传输层仅截取有界的 `stderr` 尾部作为诊断信息；如 MCP 传输规范所要求的那样，stderr 本身不被视为故障。
- `stdout` 消息在进行 JSON 解析前具有大小限制。Tool 发现机制同样拥有页面限制和 Tool 总数限制。
- 2026 版本中的 Tool 列表通知需要订阅机制，这目前尚未实现。缓存提示虽已公开，但并未被用来热突变（hot-mutate）一个已经构建完成的 Agent。必要时，请重新发现一个新的 `McpToolSet` 并构建一个新的 Agent。
- 声明了 `execution.taskSupport=required` 的遗留实验性 Tool 会导致 Tool 集合发现显式失败。2026 版的 Tasks 扩展未对外声称支持。

## 刻意推迟的特性

- 可流式的 HTTP 和传统的 HTTP+SSE 传输层；
- 授权和远程会话恢复；
- 资源（resources）和提示词（prompts）；
- 超出通用的 `McpInputHandler` 桥接范围的一等公民 roots、采样（sampling）和诱导（elicitation）API；
- 订阅和 MCP Tasks 扩展；
- MCP 服务器模式；
- 对 Agent 不可变的 Tool 注册表进行实时修改（live mutation）。

这些特性可以在 `McpClient` 之后添加，或者作为独立的能力 API 添加，而无需改变固定的 Agent 循环。
