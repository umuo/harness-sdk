# 工具结果、保存和错误

## 三个输出边界

工具输出在三个不同层面上受到控制：

1. 工具限制获取。文件读取使用窗口，搜索限制预览数量，并且进程捕获有界流。如果生产者（producer）省略了数据，它必须保留一个可读的数据源（source），或者逐步保存完整的输出。
2. `ToolResultPolicy` 是结果进入 AgentState 和模型历史记录之前的最终边界。它保护上下文免受返回意外过长字符串的自定义工具或插件工具的影响。如果没有附加可恢复的数据源，它会在生成预览之前保存那个确切的字符串。
3. 历史记录压缩（History compaction）稍后可能会在长时间运行的会话接近其模型上下文限制时移除旧的结果。压缩不属于第一个 MVP 的一部分。

默认的 `BoundedToolResultPolicy` 预留了 50 KiB 和 2,000 行空间，用于 UTF-8 安全的头/尾预览。可见的定位器（locator）永远不会被裁剪；因此，一个非常长的文件系统路径可能会超出字节预算一小部分。
元数据记录包括：

- `toolOutputTruncated`
- `toolOutputOriginalBytes`
- `toolOutputOriginalLines`
- `toolOutputRetainedBytes`
- `toolOutputStrategy`
- `toolOutputPreservation`
- `toolOutputFullPath` 当策略创建快照时

只有预览会作为工具消息附加并保留在当前的 State 中。完整内容保持在模型上下文之外。

## 可恢复输出协议

`ToolResult.outputReferences` 是工具和最终策略之间的小型协议。引用（References）包含路径、检查指令，以及以下两种类型之一：

- `SOURCE_FILE`：原始文件本身已经是权威的完整内容，就像 `read_file` 一样；
- `TEMPORARY_FILE`：完整的生产者输出被捕获在一个单独的文件中。

一个结果上的所有引用必须足以共同恢复其生产者省略的内容。策略遵循一个不变原则：

```text
no reference  -> save the exact ToolResult once -> attach reference -> preview
has reference -> reuse that source                         -> preview
```

这防止了溢出链（spill chain）。读取一个过大的已保存输出会返回一个指向该相同路径的 `SOURCE_FILE` 引用。如果读取窗口再次缩短，策略会复用该数据源，而不是写入 `copy-2`、`copy-3` 等等。

默认位置是 `${java.io.tmpdir}/agent-sdk-tool-output`。在 POSIX 文件系统上，存储会请求仅限所有者（owner-only）的目录/文件权限。在 MVP 中文件不会自动过期，因为在一个回合（Turn）中删除文件会使模型的路径无效；保留和清理机制由主机应用程序或操作系统的临时文件策略负责。

如果持久化失败，执行会显式抛出失败，而不是悄悄丢失被省略的数据。

## 生产者行为

- `read_file` 会对原始文件进行分页并附加该源路径。它绝不会仅仅因为分页受限而复制整个大型源文件。
- `glob` 会保留排序后的预览，一旦结果数量超过预览限制，便将每个匹配项延迟流式传输到一个输出文件中。
- `bash` 在进程运行时捕获原始的 stdout 和 stderr。如果任何一个被截断，那么这两个完整的流都会被保留并引用。
- `write_file`、`edit`、`apply_patch` 和 Todo 工具返回紧凑的状态数据，通常不需要生产者溢出（producer spill）。
- 自定义工具、Agent-as-Tool 和未来的工具均受到最终策略的保护。能够输出无界数据的生产者应该直接流式传输到 `ToolOutputStore`，而不是首先在内存中生成一个巨大的 `String`。

当使用自定义输出目录时，请使用相同的路径配置通用策略和工作区工具，以便 `read_file` 可以检查它：

```java
Path outputDirectory = Paths.get("/secure/runtime/agent-output");

WorkspaceTools workspace = WorkspaceTools.builder(Paths.get("."))
    .toolOutputDirectory(outputDirectory)
    .build();

Agent agent = Agent.builder()
    .instructions(workspace.getInstructions())
    .tools(workspace.getTools())
    .toolResultPolicy(new BoundedToolResultPolicy(
        32 * 1024, 1000, outputDirectory
    ))
    .build();
```

只有 `read_file` 会获得对此配置目录的读取访问权限。文件修改和 Bash 工作目录解析仍然被限定在工作区作用域内。

## 结构化错误

仅靠异常类名和堆栈跟踪很少能帮助模型恢复。工具执行失败时应包含：

- 一个稳定的大写错误代码；
- 对失败原因的简明解释；
- 重试是否有意义；
- 一个具体的恢复指令；
- 小型的结构化详细信息，例如路径或工具名称。

```java
throw new ToolFailureException(
    ToolErrorInfo.builder(
        "FILE_NOT_OBSERVED",
        "The file was not read in this Turn"
    ).retryable(true)
        .recoveryHint("Read the file, then retry the edit.")
        .detail("path", "README.md")
        .build()
);
```

使用 `REPORT_TO_MODEL`，模型将接收到：

```text
Error [FILE_NOT_OBSERVED]: The file was not read in this Turn
Recovery: Read the file, then retry the edit.
Details:
- path: README.md
```

同样的 `ToolErrorInfo` 仍然可以在 `ToolResult` 上以编程方式访问。
未声明的异常会被标准化为 `INVALID_TOOL_ARGUMENTS`、`TOOL_TIMEOUT`、`TOOL_IO_ERROR` 或 `TOOL_EXECUTION_FAILED`。在 `FAIL_FAST` 模式下，执行异常仍然会导致当前回合（Turn）失败。

## 设计参考

此设计沿用了成熟的 Harness 行为，但没有照搬其运行时模型：

- [DeepSeek Harness filesystem tools](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/fs/tool-fs)
  通过行数、单行长度和字节数来限制读取，并为陈旧的文件系统错误添加恢复指令。
- [OpenCode truncation](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/truncate.ts)
  使用最终的 2,000 行/50-KiB 工具输出边界，并保留一个清晰可见的截断通知。
- [Pi coding-agent truncation example](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/examples/extensions/truncated-tool.ts)
  将完整的结果写入操作系统的临时目录，并将其路径与预览一起返回。
- [Pi streaming output accumulator](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/src/core/tools/output-accumulator.ts)
  启动有界的流捕获，在发生截断时持久化原始输出；它的 Bash 工具返回产生的完整输出路径。
- [Codex output truncation](https://github.com/openai/codex/blob/main/codex-rs/utils/output-truncation/src/lib.rs)
  保留超大执行输出的首尾两部分，并报告原始的尺寸元数据。

SDK 将这些想法封装在 `ToolResult`、`ToolOutputStore` 和一个小型策略之后，而不是在 MVP 中直接添加事件存储或压缩框架。
