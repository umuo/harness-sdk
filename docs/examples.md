# 真实的 LLM 示例

`agent-examples` 包含可执行的自检示例。每个 Agent 均使用由实际兼容 OpenAI 的端点支持的 `OpenAiChatModel`，并且每个 Agent 都注册了与普通应用程序中使用的相同的平台 `AgentObservability` 插件。没有任何示例会安装伪造的 `ChatModel` 或返回硬编码的模型响应。

所有默认的用户任务、Agent 描述、指令、技能 (Skill) 内容以及控制台标签均为中文。可以通过命令行参数替换示例的默认任务。

## 环境

配置一个真实的兼容 OpenAI 的提供方 (Provider)：

```bash
export OPENAI_API_KEY="your-provider-key"
export LLM_BASE_URL="https://your-provider.example/v1"
export LLM_MODEL="your-tool-capable-model"
```

配置由此仓库创建的可观测性 (observability) 平台：

```bash
export AGENT_OBSERVABILITY_ENDPOINT="http://localhost:3000/api/traces"
export AGENT_OBSERVABILITY_API_KEY="your-application-ingestion-key"
```

端点默认指向 `http://localhost:3000/api/traces`，并且对于没有注册应用程序的本地平台，允许使用空的摄取 (ingestion) 密钥。追踪 (Trace) 导出是异步的，并且绝不会改变 Agent 的结果；当追踪本身属于测试的一部分时，请首先启动该平台。

所配置的模型必须支持函数/工具 (function/tool) 调用，以用于每个工具 (Tool)、子 Agent (SubAgent)、MCP、待办事项 (Todo) 和技能 (Skill) 的示例。`StreamingAgentExample` 此外还要求支持兼容 OpenAI 的 SSE 流式传输。

## 构建并运行

安装一次 reactor 制品：

```bash
mvn -q install -DskipTests
```

从仓库根目录运行一个示例：

```bash
mvn -q -pl agent-examples exec:java \
  -Dexec.mainClass=io.github.gitsilence.agent.examples.StreamingAgentExample
```

在需要时覆盖它的中文任务：

```bash
mvn -q -pl agent-examples exec:java \
  -Dexec.mainClass=io.github.gitsilence.agent.examples.OpenAiAgentExample \
  -Dexec.args="请计算 125 加 378，并说明委托过程"
```

## 包含的示例

| 类 | 验证的真实行为 |
| --- | --- |
| `OpenAiAgentExample` | 监督者 (Supervisor) 将中文算术委托给作为工具的 Agent (Agent-as-Tool) |
| `ObservabilityExample` | 真实的 Provider 请求和响应以及捕获的内容会被导出 |
| `ComplexTaskDelegationExample` | 一个复杂的任务被拆分给需求、架构和风险的子 Agent；一次多调用 (multi-call) 响应将它们在并行工具执行中运行 |
| `TodoAgentExample` | 真实模型必须行使 `ADD`、`UPDATE`、`COMPLETE` 和 `LIST`，留下至少三个完成的回合作用域 (Turn-scoped) 待办事项，然后作答 |
| `BuiltInToolsAgentExample` | 真实模型必须在受限示例工作区中调用 `glob`、`read_file`、`write_file`、`edit`、`apply_patch` 和 `bash` |
| `StreamingAgentExample` | `runStreamingAsync` 必须在得到完整结果之前接收到至少一个实际的 `TEXT_DELTA` |
| `McpAgentExample` | 发现标准输入/输出 (stdio) 的文件系统 MCP 服务器，真实模型必须至少调用一个带命名空间的 MCP 工具 |
| `SkillsAgentExample` | 真实模型必须通过 `skill_load` 渐进式地加载 `SKILL.md` 和 `references/template.md` |

当要求的行为没有发生时，示例会显式失败。这使得它们适合作为手动 Provider 兼容性检查，而不是可能未调用预期能力而默默成功的演示 (demos)。

## 并行子 Agent

`ComplexTaskDelegationExample` 在一个普通的监督者上将三个 Agent 注册为工具，并启用：

```java
.parallelToolCalls(true)
```

监督者指令要求在一次模型响应中进行所有三个调用。
然后现有的 `ToolExecutor` 通过 `CompletableFuture` 并发地运行 Agent 工具。所有四个 Agent 共享一个线程安全的可观测性插件，同时每个子 Agent 保持一个隔离的可变状态 (State)。生成的回合分段被可观测性平台显示为一个由调用者触发的任务 (Task)。

## 内置工具安全

默认情况下，内置工具示例使用 `agent-examples/target/builtin-tools-workspace`，并仅在该专用目录中重新创建 `source.txt` 和 `report.md`。如果需要，可设置一个显式的工作区：

```bash
export AGENT_EXAMPLE_WORKSPACE="/absolute/path/to/a/disposable-workspace"
```

该示例故意启用了 Bash。请仅使用一次性工作区和受信任的任务。SDK 的工作区边界不是一个操作系统沙盒。

## MCP 示例

MCP 示例启动了在 [MCP client and Tool integration](mcp.md) 中记录的相同文件系统服务器形态：

```text
npx -y @modelcontextprotocol/server-filesystem <workspace>
```

它需要 Node.js 和 `npx`。可选配置：

```bash
export MCP_WORKSPACE="/absolute/readable/workspace"
export MCP_COMMAND="/absolute/path/to/npx"
export MCP_FILESYSTEM_PACKAGE="@modelcontextprotocol/server-filesystem"
```

Agent 指令将示例限制为列出和读取，但远程服务器可能会通告修改类的工具。请仅运行受信任的 MCP 包，并在仅指令的限制不够时使用操作系统沙盒或工具拦截器。

## 技能示例

签入 (checked-in) 的技能位于：

```text
agent-examples/skills/chinese-release-note/
├── SKILL.md
└── references/template.md
```

`SkillsAgentExample` 通过 `skillsFrom` 加载该目录。只有发现的元数据会进入初始系统提示词。该示例验证模型随后对主指令调用了 `skill_load`，并单独对引用的模板进行调用。可以使用 `AGENT_EXAMPLE_SKILLS_DIR=/absolute/path` 覆盖根目录。

## 显式的计费集成测试

常规的 `mvn test` 会编译这些集成测试，但会跳过所有真实的物理网络调用。通过以下命令显式启用它们：

```bash
export RUN_REAL_LLM_EXAMPLES=true

mvn -pl agent-examples -am \
  -Dtest=RealLlmExamplesIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

这将运行复杂委托、待办事项、所有内置工具、流式传输和技能检查，并可能产生许多计费的模型请求。单独启用外部 MCP 进程测试：

```bash
export RUN_MCP_EXAMPLE=true
```

仅运行流式传输检查，使用：

```bash
mvn -pl agent-examples -am \
  '-Dtest=RealLlmExamplesIntegrationTest#receivesRealStreamingDeltas' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

仓库不包含 Provider 凭据。CI 应仅针对显式批准、成本受限的集成作业从其机密存储中注入密钥。
