# Built-in Workspace Tools

The built-ins use the typed `AbstractTool<I>` / `AbstractAsyncTool<I>` APIs;
their model-facing schemas are generated from input classes rather than kept as
handwritten JSON strings.

## Module and setup

The optional `agent-tools-builtin` module contains a coding-oriented Tool suite
without adding filesystem or process APIs to `agent-core`:

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
    .skill(workspace.asSkill())
    .build();
```

`bash` is disabled unless explicitly enabled. The file and search Tools are
registered by default.

## Tool contracts

| Tool | Important arguments | Bounded behavior |
| --- | --- | --- |
| `read_file` | `file_path`, `offset?`, `limit?` | 1-based numbered UTF-8 windows; defaults to at most 2,000 lines, 2,000 characters per line and 50 KiB |
| `write_file` | `file_path`, `content` | atomic create/full replacement; defaults to a 5-MiB input limit |
| `edit` | `file_path`, `old_string`, `new_string`, `replace_all?` | exact literal replacement; unique match required unless `replace_all` is true; defaults to 5-MiB files |
| `glob` | `pattern`, `path?` | files only, no symlink traversal, VCS metadata skipped; sorted preview capped at 100, with all matches persisted on overflow |
| `bash` | `command`, `workdir?`, `timeout_ms?` | separate bounded stdout/stderr preview, timeout and exit marker; both raw streams persisted when either overflows |

A glob pattern without `/` matches basenames at every depth. Capped reads tell
the model which offset to request next. A capped glob returns both a narrowing
hint and a path containing every match in traversal order.

## Workspace boundary

Relative paths resolve against one configured root. Absolute paths and `..`
cannot escape it by default. Existing ancestors are resolved through the real
filesystem path before the boundary is checked, which also blocks a symlink
inside the workspace from redirecting a file Tool outside it.

There is one narrow exception: `read_file` may read files under the configured
`toolOutputDirectory` so the model can inspect complete Tool output. This does
not grant `write_file`, `edit`, or Bash working-directory access outside the
workspace. The default output directory is
`${java.io.tmpdir}/agent-sdk-tool-output`.

This is an application guard, not an operating-system security sandbox. There
is an unavoidable check/write race against unrelated external processes, and
`allowOutsideWorkspace(true)` deliberately removes the boundary.

## Read-before-mutation policy

By default, overwriting or editing an existing file requires a successful
`read_file` in the same Turn. The read records a SHA-256 observation in that
Turn's private State. Before mutation the Tool hashes the current file again:

- no observation → `FILE_NOT_OBSERVED`, with “read then retry” guidance;
- changed content → `FILE_CHANGED_SINCE_READ`, with “re-read then retry”
  guidance;
- successful create/write/edit records the new observation, so a later edit in
  the same Turn can proceed.

This avoids stale LLM edits and mirrors the observation policy used by mature
coding Harnesses. It can be disabled with
`requireReadBeforeMutation(false)` when another policy layer owns concurrency.

## Bash behavior and security

`bash` runs `<executable> -c <command>` with its working directory inside the
workspace. It reports stdout, a marked stderr section, timeout and exit code.
Non-zero exit and timeout results carry structured `COMMAND_EXIT_NON_ZERO` or
`COMMAND_TIMED_OUT` errors plus a recovery instruction.

Each stream keeps a bounded head/tail preview. Complete raw streams are captured
under `toolOutputDirectory`; the files are deleted when neither stream was
truncated and both are retained when either stream was truncated. Keeping both
means a later Agent-wide context bound can still recover everything without
creating another combined-output copy. `bashSpillDirectory` remains available
as a Bash-only override.

Output files may contain secrets and are not automatically expired in the
MVP—the application or operating-system temp policy owns retention and cleanup.
Failure to prepare or write complete capture returns
`OUTPUT_PRESERVATION_FAILED` or `OUTPUT_CAPTURE_FAILED`; it never silently
reports a lossy success.

The Bash Tool is intentionally **not a sandbox**. It inherits the Java process
authority and environment, and a command can access paths outside the
workspace. On Java 8, cancellation can forcibly stop the direct Bash process
but cannot portably guarantee whole-process-tree termination. Production
deployments should place the JVM/process in an OS sandbox or add an approval
`ToolInterceptor`. Keep the Agent's generic `toolTimeout` longer than the Bash
Tool's maximum timeout if partial timeout output must be returned to the model.

## Error shape

Filesystem and process errors use stable codes and recovery text instead of
returning Java stack traces. Examples include:

- `PATH_OUTSIDE_WORKSPACE`
- `FILE_NOT_FOUND`, `BINARY_FILE`, `INVALID_UTF8`
- `FILE_NOT_OBSERVED`, `FILE_CHANGED_SINCE_READ`
- `EDIT_TEXT_NOT_FOUND`, `EDIT_TEXT_NOT_UNIQUE`
- `GLOB_INVALID_PATTERN`, `GLOB_SCAN_LIMIT`
- `COMMAND_START_FAILED`, `COMMAND_EXIT_NON_ZERO`, `COMMAND_TIMED_OUT`
- `OUTPUT_PRESERVATION_FAILED`, `OUTPUT_CAPTURE_FAILED`

These errors still pass through the Agent-wide `ToolResultPolicy` before they
enter model history.

## Mature implementation references

The first version deliberately adopts established behavior from primary
implementations:

- [DeepSeek Harness filesystem Tools](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/fs/tool-fs):
  paginated numbered reads, per-line/byte limits, exact edit semantics and
  read-before-mutation recovery.
- [DeepSeek Harness search Tools](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/fs/tool-fs-search):
  result/raw-scan budgets, clear empty results, search narrowing guidance and
  VCS metadata exclusions.
- [DeepSeek Harness Bash Tool](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/shell/tool-bash):
  separate stdout/stderr, non-zero/timeout facts, bounded capture and spill
  notices.
- [OpenCode built-in Tools](https://github.com/anomalyco/opencode/tree/dev/packages/opencode/src/tool):
  the familiar read/write/edit/glob contracts and concise mutation results.
- [Pi output accumulator](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/src/core/tools/output-accumulator.ts):
  bounded process previews with recoverable full-output files.

The Java SDK keeps the public surface smaller: there is no permission DSL,
background job runtime, packaged ripgrep binary or dynamic Tool router in this
milestone.
