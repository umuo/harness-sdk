# Agent Skills

## What a Skill is

A Skill is a portable, file-backed package of instructions and supporting
resources that the model loads only when a task needs it. It is not a Java
bundle of `instructions + List<Tool>`, and it does not introduce another Agent
Loop or routing engine.

Skills are trusted agent instructions and may reference executable code.
Applications should review a Skill before registering it, especially when it
comes from a third party or a writable project directory.

The supported layout follows the Agent Skills specification:

```text
code-review/
├── SKILL.md
├── scripts/       # optional helper code
├── references/    # optional detailed text
└── assets/        # optional templates or binary resources
```

`SKILL.md` starts with YAML frontmatter followed by Markdown instructions:

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

`name` and `description` are required. Names use lowercase letters, numbers and
single hyphens, are at most 64 characters, and must match the Skill directory.
Descriptions are at most 1,024 characters. `compatibility`, `license`,
`allowed-tools`, and string-to-string `metadata` are optional.

## Register Skills

Load one Skill from its directory or entry file:

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

The builder also provides strict recursive discovery:

```java
Agent agent = Agent.builder()
    .name("assistant")
    .description("Uses project Skills")
    .model(chatModel)
    .skillsFrom(Paths.get(".agents/skills"))
    .build();
```

Strict discovery rejects the registration if any discovered Skill is invalid.
For an application-controlled warning policy, inspect the result directly:

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

Discovery is deterministic, does not follow directory symlinks, and reports
invalid neighboring Skills without hiding valid ones. Duplicate Skill names
are rejected when the immutable `SkillRegistry` is built.

## Progressive loading

Building the Agent adds only Skill name, description and location to its system
prompt. Full Markdown instructions are not read into model context. When the
model decides a Skill matches, it calls the automatically registered Tool:

```json
{
  "name": "skill_load",
  "arguments": {
    "name": "code-review"
  }
}
```

The Tool strips YAML frontmatter and returns the Markdown body. That Tool result
is appended to the same Turn's State and becomes available to the next model
step through the ordinary Agent Loop.

References use the same Tool with a relative resource path:

```json
{
  "name": "skill_load",
  "arguments": {
    "name": "code-review",
    "resource": "references/checklist.md"
  }
}
```

This gives three context levels without a Skill Router:

1. All registered names and descriptions are available at startup.
2. A matching `SKILL.md` body loads after activation.
3. Referenced text loads only when the instructions require it.

## Tools, scripts, and permissions

Skills do not contain live Java `Tool` instances. Register executable Tools on
the Agent independently. The experimental `allowed-tools` field is parsed as
descriptor metadata only; this SDK does not treat it as registration,
authorization, or an approval bypass.

Files under `scripts/` are resources, not automatically executed programs. A
model can inspect them with `skill_load`, but execution requires an explicitly
registered process Tool and remains subject to that Tool's policy. Likewise,
binary assets should be referenced by path rather than inserted into model
context.

The former `WorkspaceTools.asSkill()` API was removed for this reason.
Workspace tools are registered as a Tool set, with their guidance composed
explicitly:

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

## Resource boundaries

`skill_load` accepts only registered Skill names and relative resource paths.
Normalized paths and resolved symbolic links must remain beneath the Skill
root. Missing, non-regular, binary, and invalid UTF-8 resources produce stable
model-facing errors with recovery guidance.

Each load is capped at 512 KiB before decoding. The normal Agent
`ToolResultPolicy` still applies its smaller model-context limit. Skill results
carry a source-file reference, so truncation points back to the installed Skill
instead of creating a redundant temporary copy.

The design follows the progressive-disclosure model documented by the
[Agent Skills specification](https://agentskills.io/specification) and
[Pi's Skill implementation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/skills.md).
