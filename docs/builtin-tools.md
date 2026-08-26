# 内置工作区工具

内置组件使用强类型的 `AbstractTool<I>` / `AbstractAsyncTool<I>` API；
它们面向模型的 schema 是通过输入类生成的，而不是作为手写 JSON 字符串保留。

## 模块和设置

可选的 `agent-tools-builtin` 模块包含一个面向编码的工具套件，
而没有向 `agent-core` 中添加文件系统或进程 API：

```xml
<dependency>
  <groupId>io.github.gitsilence</groupId>
  <artifactId>agent-tools-builtin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
WorkspaceTools workspace = WorkspaceTools.builder(Paths.get("."))
    .enableBash(true)
    .build();

Agent codingAgent = Agent.builder()
    .name("coding-agent")
    .model(chatModel)
    .instructions(workspace.getInstructions())
    .tools(workspace.getTools())
    .build();
```

除非显式启用，否则 `bash` 默认禁用。文件、补丁和搜索工具包含在 `getTools()` 中。`WorkspaceTools` 是一个工具集，而不是一个 Agent 技能；
建议将其指令（instructions）显式地组合到 Agent 中。

## 工具契约

| 工具 | 重要参数 | 边界行为 |
| --- | --- | --- |
| `read_file` | `file_path`, `offset?`, `limit?` | 基于 1 索引且带行号的 UTF-8 窗口；默认最多返回 2,000 行，每行 2,000 个字符以及 50 KiB 大小限制 |
| `write_file` | `file_path`, `content` | 原子化创建/完全替换；默认输入限制为 5-MiB |
| `edit` | `file_path`, `old_string`, `new_string`, `replace_all?` | 精确字面替换；除非 `replace_all` 为 true，否则要求唯一匹配；默认适用于最大 5-MiB 的文件 |
| `apply_patch` | `patch` | 一次调用新增、更新、移动或删除多个 UTF-8 文件；先完成整份补丁预检，再执行有回滚保护的写入 |
| `glob` | `pattern`, `path?` | 仅匹配文件，不遍历符号链接，跳过 VCS 元数据；排序后的预览最多显示 100 个结果，超出限制时会持久化保存所有匹配结果 |
| `bash` | `command`, `workdir?`, `timeout_ms?` | 分离并具有边界的 stdout/stderr 预览，超时和退出标记；当任一流超出限制时，这两个原始流都会被持久化保存 |

没有 `/` 的 glob 模式会在任意深度匹配基础名称（basenames）。达到上限的读取结果会提示模型下一次应该请求哪个偏移量（offset）。达到上限的 glob 结果会返回一个缩小范围的提示（narrowing hint），以及一个包含按遍历顺序排列的所有匹配结果的文件路径。

## 多文件补丁

`apply_patch` 使用与 Codex 相同的核心标记和行前缀。例如：

```text
*** Begin Patch
*** Add File: docs/new.md
+new file
*** Update File: src/App.java
*** Move to: src/Main.java
@@ public void run()
-        oldCall();
+        newCall();
*** Delete File: docs/obsolete.md
*** End Patch
```

支持 `*** Add File`、`*** Delete File`、`*** Update File`、紧随更新头的可选 `*** Move to`、`@@` 上下文提示和 `*** End of File`。更新块中的空格、`-`、`+` 分别代表上下文、删除和新增行。定位时依次尝试精确匹配、忽略行尾空白、忽略两侧空白；未修改的上下文行会保留原有行结束符，新增行沿用文件首次出现的 LF、CRLF 或 CR 风格。

一次补丁中的每个源路径或目标路径只能出现一次。这样可以把所有受影响路径按固定顺序加锁，避免并发补丁死锁，也能在写盘前完成全部路径、UTF-8、版本、上下文和大小检查。逻辑预检失败时不会写入任何文件；I/O 阶段失败时会尽力恢复原文件，并用 `PATCH_APPLY_FAILED` 或 `PATCH_ROLLBACK_FAILED` 区分恢复结果。进程崩溃和工作区外部进程的竞态仍需要操作系统级事务或沙箱来解决。

默认限制为：补丁文本 1 MiB、100 个不同路径、预检读取与生成文本合计 20 MiB，单个新增/更新文件仍分别受 `maxWriteBytes` / `maxEditableBytes` 约束。可通过 `maxPatchBytes`、`maxPatchFiles` 和 `maxPatchAffectedBytes` 调整。

## 工作区边界

相对路径将相对于配置的一个根目录进行解析。默认情况下，绝对路径和 `..` 无法逃逸此根目录。
在检查边界之前，任何存在的祖先目录都会通过真实文件系统路径进行解析，这也会阻止工作区内的符号链接将文件工具重定向到工作区之外。

这里有一个狭窄的例外情况：`read_file` 可以读取配置的 `toolOutputDirectory` 目录下的文件，以便模型能够检查完整的工具输出。
但这并不赋予 `write_file`、`edit`、`apply_patch` 或 Bash 在工作区外拥有工作目录访问权限。默认的输出目录是 `${java.io.tmpdir}/agent-sdk-tool-output`。

这是一个应用程序级别的防护，而不是操作系统的安全沙箱。针对不相关的外部进程，不可避免地会存在检查/写入竞态条件，并且 `allowOutsideWorkspace(true)` 会故意移除此边界。

## 变更前读取策略

默认情况下，覆盖、编辑、删除或移动一个已存在的文件需要在同一个回合（Turn）中成功执行过 `read_file`。`apply_patch` 的已存在移动目标也必须先读取。
读取操作会在该回合的私有状态（State）中记录一个 SHA-256 的观察结果。在进行内容变更前，该工具会再次对当前文件进行哈希计算：

- 无观察结果 → `FILE_NOT_OBSERVED`，并提供“先读取再重试”的指导建议；
- 内容已更改 → `FILE_CHANGED_SINCE_READ`，并提供“重新读取再重试”的指导建议；
- 成功的创建、写入、编辑或补丁操作会记录新的观察结果，以便同一回合中后续修改能够继续进行。

这种机制可以避免过时的 LLM 编辑，并反映了成熟编码脚手架（Harnesses）所使用的观察策略。
当并发控制由其他策略层拥有时，可以通过 `requireReadBeforeMutation(false)` 来禁用它。

## Bash 行为与安全性

`bash` 会在工作区内作为其工作目录运行 `<executable> -c <command>`。它会报告 stdout、一个带有标记的 stderr 区域、超时情况以及退出状态码。
非零的退出状态和超时结果将携带结构化的 `COMMAND_EXIT_NON_ZERO` 或 `COMMAND_TIMED_OUT` 错误以及恢复指令。

每个流保留一个有限大小的头部/尾部预览。完整的原始流会被捕获到 `toolOutputDirectory` 下；当两个流都未被截断时，这些文件将被删除；而当任一流被截断时，两者都会被保留。
保留两者意味着后续 Agent 级别的上下文限制仍可恢复所有内容，而无需创建另一份合并输出的副本。`bashSpillDirectory` 依然作为仅适用于 Bash 的覆盖配置提供。

输出文件可能包含敏感信息（secrets），并且在 MVP 阶段不会被自动过期清理——应用程序或操作系统的临时文件策略负责它的保留与清理。
如果未能准备或写入完整的捕获结果，将返回 `OUTPUT_PRESERVATION_FAILED` 或 `OUTPUT_CAPTURE_FAILED`；它永远不会静默报告一个有损的成功。

Bash 工具在设计上**不是一个沙箱**。它继承了 Java 进程的权限和环境变量，并且命令可以访问工作区外部的路径。
在 Java 8 上，取消操作能够强制停止直接启动的 Bash 进程，但由于平台移植性问题，无法保证整个进程树的终止。
生产环境部署时应该将 JVM/进程放置在 OS 沙箱中，或者添加一个需要审批的 `ToolInterceptor`。
如果必须将部分超时输出返回给模型，请保持 Agent 通用的 `toolTimeout` 超过 Bash 工具的最大超时时间。

## 错误形式

文件系统和进程错误使用稳定的错误代码和恢复文本，而不是返回 Java 的堆栈跟踪。例如包括：

- `PATH_OUTSIDE_WORKSPACE`
- `FILE_NOT_FOUND`, `BINARY_FILE`, `INVALID_UTF8`
- `FILE_NOT_OBSERVED`, `FILE_CHANGED_SINCE_READ`
- `EDIT_TEXT_NOT_FOUND`, `EDIT_TEXT_NOT_UNIQUE`
- `PATCH_INVALID`, `PATCH_CONTEXT_NOT_FOUND`, `PATCH_PATH_CONFLICT`
- `PATCH_TOO_LARGE`, `PATCH_TOO_MANY_FILES`, `PATCH_AFFECTED_BYTES_EXCEEDED`
- `PATCH_APPLY_FAILED`, `PATCH_ROLLBACK_FAILED`
- `GLOB_INVALID_PATTERN`, `GLOB_SCAN_LIMIT`
- `COMMAND_START_FAILED`, `COMMAND_EXIT_NON_ZERO`, `COMMAND_TIMED_OUT`
- `OUTPUT_PRESERVATION_FAILED`, `OUTPUT_CAPTURE_FAILED`

这些错误在进入模型历史记录之前，仍会通过 Agent 级别的 `ToolResultPolicy` 处理。

## 成熟的实现参考

最初版本有意采用了来自主要实现的既定行为：

- [DeepSeek Harness filesystem Tools](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/fs/tool-fs):
  支持带行号的分页读取、单行/字节数限制、精确编辑语义以及变更前读取恢复策略。
- [DeepSeek Harness search Tools](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/fs/tool-fs-search):
  结果数量/原始扫描量的预算机制，清晰说明的空结果，搜索范围缩小指导以及 VCS 元数据排除。
- [DeepSeek Harness Bash Tool](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/shell/tool-bash):
  分离的 stdout/stderr，非零/超时事实记录，有限度的输出捕获和溢出提示。
- [OpenCode built-in Tools](https://github.com/anomalyco/opencode/tree/dev/packages/opencode/src/tool):
  熟悉的读取/写入/编辑/glob契约和简明的修改结果。
- [Pi output accumulator](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/src/core/tools/output-accumulator.ts):
  具备有限制的进程结果预览，同时提供可恢复的完整输出文件。
- [Codex apply-patch](https://github.com/openai/codex/tree/main/codex-rs/apply-patch):
  多文件补丁标记、顺序上下文匹配、EOF 约束以及行结束符保留。

Java SDK 保持较小的公开接口暴露：在此里程碑中，没有权限 DSL、后台任务运行时环境、打包的 ripgrep 二进制文件或动态工具路由器。
