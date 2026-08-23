# Real LLM Examples

`agent-examples` contains executable, self-checking examples. Every Agent uses
an `OpenAiChatModel` backed by an actual OpenAI-compatible endpoint and every
Agent registers the same platform `AgentObservability` plugin used in normal
applications. No example installs a fake `ChatModel` or returns a hard-coded
model response.

All default user tasks, Agent descriptions, instructions, Skill content, and
console labels are Chinese. A command-line argument can replace an example's
default task.

## Environment

Configure a real OpenAI-compatible Provider:

```bash
export OPENAI_API_KEY="your-provider-key"
export LLM_BASE_URL="https://your-provider.example/v1"
export LLM_MODEL="your-tool-capable-model"
```

Configure the observability platform created by this repository:

```bash
export AGENT_OBSERVABILITY_ENDPOINT="http://localhost:3000/api/traces"
export AGENT_OBSERVABILITY_API_KEY="your-application-ingestion-key"
```

The endpoint defaults to `http://localhost:3000/api/traces`, and an empty
ingestion Key is permitted for a local platform that has no registered
applications. Trace export is asynchronous and never changes the Agent result;
start the platform first when the trace itself is part of the test.

The configured model must support function/tool calls for every Tool,
SubAgent, MCP, Todo, and Skill example. `StreamingAgentExample` additionally
requires OpenAI-compatible SSE streaming.

## Build and run

Install the reactor artifacts once:

```bash
mvn -q install -DskipTests
```

Run an example from the repository root:

```bash
mvn -q -pl agent-examples exec:java \
  -Dexec.mainClass=io.github.gitsilence.agent.examples.StreamingAgentExample
```

Override its Chinese task when needed:

```bash
mvn -q -pl agent-examples exec:java \
  -Dexec.mainClass=io.github.gitsilence.agent.examples.OpenAiAgentExample \
  -Dexec.args="请计算 125 加 378，并说明委托过程"
```

## Included examples

| Class | Real behavior checked |
| --- | --- |
| `OpenAiAgentExample` | A Supervisor delegates Chinese arithmetic to an Agent-as-Tool |
| `ObservabilityExample` | A real Provider request and response is exported with captured content |
| `ComplexTaskDelegationExample` | One complex task is split across requirements, architecture, and risk SubAgents; one multi-call response runs them through parallel Tool execution |
| `TodoAgentExample` | The real model must exercise `ADD`, `UPDATE`, `COMPLETE`, and `LIST`, leave at least three Turn-scoped Todos completed, then answer |
| `BuiltInToolsAgentExample` | The real model must call `glob`, `read_file`, `write_file`, `edit`, and `bash` inside a bounded example workspace |
| `StreamingAgentExample` | `runStreamingAsync` must receive at least one actual `TEXT_DELTA` before the completed result |
| `McpAgentExample` | A stdio filesystem MCP server is discovered and at least one namespaced MCP Tool must be called by the real model |
| `SkillsAgentExample` | The real model must progressively load both `SKILL.md` and `references/template.md` through `skill_load` |

Examples fail explicitly when the required behavior does not occur. This makes
them useful as manual Provider compatibility checks instead of demos that can
silently succeed without calling the intended capability.

## Parallel SubAgents

`ComplexTaskDelegationExample` registers three Agents as Tools on an ordinary
Supervisor and enables:

```java
.parallelToolCalls(true)
```

The Supervisor instruction requires all three calls in one model response.
The existing `ToolExecutor` then runs the Agent Tools concurrently through
`CompletableFuture`. All four Agents share one thread-safe observability
plugin, while every SubAgent keeps an isolated mutable State. The resulting
Turn segments are displayed as one caller-triggered Task by the observability
platform.

## Built-in Tool safety

The built-in Tool example uses
`agent-examples/target/builtin-tools-workspace` by default and recreates only
`source.txt` and `report.md` inside that dedicated directory. Set an explicit
workspace if desired:

```bash
export AGENT_EXAMPLE_WORKSPACE="/absolute/path/to/a/disposable-workspace"
```

The example deliberately enables Bash. Use only a disposable workspace and a
trusted task. The SDK's workspace boundary is not an operating-system sandbox.

## MCP example

The MCP example starts the same filesystem server shape documented in
[MCP client and Tool integration](mcp.md):

```text
npx -y @modelcontextprotocol/server-filesystem <workspace>
```

It requires Node.js and `npx`. Optional configuration:

```bash
export MCP_WORKSPACE="/absolute/readable/workspace"
export MCP_COMMAND="/absolute/path/to/npx"
export MCP_FILESYSTEM_PACKAGE="@modelcontextprotocol/server-filesystem"
```

The Agent instruction restricts the example to listing and reading, but the
remote server may advertise mutation Tools. Run only trusted MCP packages and
use an OS sandbox or Tool interceptor when instruction-only restrictions are
insufficient.

## Skills example

The checked-in Skill lives under:

```text
agent-examples/skills/chinese-release-note/
├── SKILL.md
└── references/template.md
```

`SkillsAgentExample` loads that directory with `skillsFrom`. Only discovery
metadata enters the initial system prompt. The example verifies that the model
then calls `skill_load` for the main instructions and separately for the
referenced template. Override the root with
`AGENT_EXAMPLE_SKILLS_DIR=/absolute/path`.

## Explicit billable integration tests

Normal `mvn test` compiles these integration tests but skips all real network
calls. Enable them explicitly:

```bash
export RUN_REAL_LLM_EXAMPLES=true

mvn -pl agent-examples -am \
  -Dtest=RealLlmExamplesIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

This runs the complex delegation, Todo, all built-in Tools, streaming, and
Skills checks and may make many billable model requests. Enable the external
MCP process test separately:

```bash
export RUN_MCP_EXAMPLE=true
```

Run only the streaming check with:

```bash
mvn -pl agent-examples -am \
  '-Dtest=RealLlmExamplesIntegrationTest#receivesRealStreamingDeltas' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

The repository does not contain Provider credentials. CI should inject Keys
from its secret store only for an explicitly approved, cost-bounded integration
job.
