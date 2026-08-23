# Agent SDK

一个轻量级的 Java 8 LLM 智能体（Agent）外壳（Harness）SDK，围绕一个小型、状态驱动的智能体循环构建。

## 模块

- `agent-core`: 与提供商无关的智能体、状态、工具、插件、可观测性、技能（skill）和待办事项（todo）运行时。
- `agent-model-http`: Java 8 HTTP/SSE 运行时，外加兼容 OpenAI 的聊天补全（Chat Completions）、OpenAI Responses API 和 Anthropic Messages 适配器。
- `agent-mcp`: Java 8 MCP 2026-07-28/旧版客户端、stdio 传输、工具发现、多轮交互输入和本地工具适配器。
- `agent-tools-builtin`: 受限工作空间的文件、glob 匹配、编辑和可选的 Bash 工具。
- `agent-examples`: 可执行的示例。
- `agent-observability-web`: 独立的 Next.js 追踪（trace）摄取服务和仪表盘（不属于 Maven reactor 的一部分）。

所有包含的模型提供商都支持完整响应和流式响应。智能体通过 `runStreamingAsync` 暴露模型增量和执行生命周期事件，而不改变固定的状态驱动循环。

## 构建

```bash
mvn clean verify
```

## 文档

- [架构](architecture.md)
- [API 指南](api-guide.md)
- [工具编写](tools.md)
- [智能体技能](skills.md)
- [模型提供商与流式处理](model-providers.md)
- [MCP 客户端与工具集成](mcp.md)
- [生命周期事件与插件](plugins.md)
- [智能体可观测性](observability.md)
- [可观测性 Web 平台](observability-platform.md)
- [工具结果、保存与错误](tool-results.md)
- [内置工作空间工具](builtin-tools.md)
- [多智能体组合](multi-agent.md)
- [真实的 LLM 示例与集成检查](examples.md)
- [兼容 OpenAI 的提供商](openai-provider.md)
- [MVP 范围与路线图](mvp.md)

该项目有意不实现图 DSL（graph DSL）、工作流引擎（workflow engine）、检查点存储（checkpoint store）、RAG 系统或向量存储（vector store）。
