# Agent 技能 (Agent Skills)

## 什么是技能 (What a Skill is)

技能（Skill）是一种便携的、基于文件的指令和支持资源的包，模型仅在任务需要时才加载它。它不是 `instructions + List<Tool>` 的 Java 包 (bundle)，也不引入另一个 Agent 循环 (Agent Loop) 或路由引擎 (routing engine)。

技能是受信任的 Agent 指令，并且可能引用可执行代码。应用程序在注册技能之前应该对其进行审查，特别是当它来自第三方或可写的项目目录时。

支持的布局遵循 Agent Skills 规范：

```text
code-review/
├── SKILL.md
├── scripts/       # optional helper code
├── references/    # optional detailed text
└── assets/        # optional templates or binary resources
```

`SKILL.md` 以 YAML frontmatter 开头，随后是 Markdown 指令：

```markdown
---
name: code-review
description: Reviews Java changes. Use when the user asks for a code review.
license: Apache-2.0
compatibility: Requires a Java project
allowed-tools: read_file edit
metadata:
  author: "example"
  version: "1"
---

# Code review

Inspect correctness before style. Read `references/checklist.md` when needed.
```

`name` 和 `description` 是必需的。名称使用小写字母、数字和单连字符，最多 64 个字符，并且必须与技能目录匹配。描述最多 1,024 个字符。`compatibility`、`license`、`allowed-tools` 和字符串键值对 `metadata` 是可选的。

## 注册技能 (Register Skills)

从其目录或入口文件加载一个技能：

```java
Skill review = SkillLoader.load(
    Paths.get(".agents/skills/code-review")
);

Agent agent = Agent.builder()
    .name("reviewer")
    .description("Reviews code")
    .model(chatModel)
    .skill(review)
    .build();
```

构建器还提供严格的递归发现 (recursive discovery)：

```java
Agent agent = Agent.builder()
    .name("assistant")
    .description("Uses project Skills")
    .model(chatModel)
    .skillsFrom(Paths.get(".agents/skills"))
    .build();
```

如果任何发现的技能无效，严格发现将拒绝注册。要使用应用程序控制的警告策略，请直接检查结果：

```java
SkillLoader.Discovery discovery = SkillLoader.discover(skillRoot);

for (SkillLoader.Diagnostic diagnostic : discovery.getDiagnostics()) {
    System.err.println(diagnostic.getMessage());
}

Agent agent = Agent.builder()
    .name("assistant")
    .description("Uses valid Skills")
    .model(chatModel)
    .skills(discovery.getSkills())
    .build();
```

发现是确定性的，不遵循目录符号链接，并且会报告无效的相邻技能而不会隐藏有效的技能。当构建不可变的 `SkillRegistry` 时，重复的技能名称会被拒绝。

## 渐进式加载 (Progressive loading)

构建 Agent 时仅将技能的名称、描述和位置添加到其系统提示词 (system prompt) 中。完整的 Markdown 指令不会读入模型上下文中。当模型决定匹配某个技能时，它会调用自动注册的 Tool：

```json
{
  "name": "skill_load",
  "arguments": {
    "name": "code-review"
  }
}
```

该 Tool 剥离 YAML frontmatter 并返回 Markdown 主体。该 Tool 的结果会附加到同一轮次 (Turn) 的状态 (State) 中，并通过普通的 Agent 循环提供给下一个模型步骤。

引用使用带有相对资源路径的同一个 Tool：

```json
{
  "name": "skill_load",
  "arguments": {
    "name": "code-review",
    "resource": "references/checklist.md"
  }
}
```

这在没有技能路由器 (Skill Router) 的情况下提供了三个上下文级别：

1. 启动时所有注册的名称和描述都可用。
2. 匹配的 `SKILL.md` 主体在激活后加载。
3. 引用的文本仅在指令需要时才加载。

## 工具、脚本和权限 (Tools, scripts, and permissions)

技能不包含活动的 Java `Tool` 实例。请在 Agent 上独立注册可执行工具。实验性的 `allowed-tools` 字段仅作为描述符元数据 (descriptor metadata) 解析；此 SDK 不将其视为注册、授权或批准绕过。

`scripts/` 下的文件是资源，而不是自动执行的程序。模型可以使用 `skill_load` 检查它们，但执行需要明确注册的进程 Tool (process Tool)，并仍然受该 Tool 策略的约束。同样，应通过路径引用二进制资产，而不是将其插入到模型上下文中。

由于这个原因，以前的 `WorkspaceTools.asSkill()` API 已被移除。工作区工具作为 Tool 集合注册，其指导是明确组合的：

```java
WorkspaceTools workspace = WorkspaceTools.builder(Paths.get(".")).build();

Agent agent = Agent.builder()
    .name("coding-agent")
    .description("Edits the workspace")
    .model(chatModel)
    .instructions(workspace.getInstructions())
    .tools(workspace.getTools())
    .build();
```

## 资源边界 (Resource boundaries)

`skill_load` 仅接受已注册的技能名称和相对资源路径。规范化路径和解析的符号链接必须保持在技能根目录之下。丢失、非规范、二进制和无效的 UTF-8 资源会产生稳定的面向模型的错误及恢复指导。

在解码之前，每次加载的上限为 512 KiB。普通的 Agent `ToolResultPolicy` 仍然应用其较小的模型上下文限制。技能结果带有源文件引用，因此截断点指向已安装的技能，而不是创建一个多余的临时副本。

该设计遵循由 [Agent Skills 规范](https://agentskills.io/specification) 和 [Pi 的 Skill 实现](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/skills.md) 记录的渐进式披露模型 (progressive-disclosure model)。
