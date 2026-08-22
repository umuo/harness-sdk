# Tool Results, Preservation and Errors

## Three output boundaries

Tool output is controlled at three different layers:

1. The Tool bounds acquisition. File reads use windows, searches cap previews,
   and processes capture bounded streams. A producer that omits data must keep
   a readable source or incrementally persist the complete output.
2. `ToolResultPolicy` is the final boundary before a result enters AgentState
   and model history. It protects the context from custom or plugin-provided
   Tools that return an unexpectedly large string. If no recoverable source is
   attached, it saves that exact string before making the preview.
3. History compaction may later remove old results when a long-running session
   approaches its model context limit. Compaction is not part of the first MVP.

The default `BoundedToolResultPolicy` budgets 50 KiB and 2,000 lines for a
UTF-8-safe head/tail preview. The visible locator is never cut; an unusually
long filesystem path can therefore add a small amount beyond the byte budget.
Metadata records:

- `toolOutputTruncated`
- `toolOutputOriginalBytes`
- `toolOutputOriginalLines`
- `toolOutputRetainedBytes`
- `toolOutputStrategy`
- `toolOutputPreservation`
- `toolOutputFullPath` when the policy created the snapshot

Only the preview is appended as a Tool message and retained in the current
State. The complete content stays outside model context.

## Recoverable-output protocol

`ToolResult.outputReferences` is the small protocol between a Tool and the
final policy. References have a path, an inspection instruction, and one of two
kinds:

- `SOURCE_FILE`: the original source is already the authoritative complete
  content, as with `read_file`;
- `TEMPORARY_FILE`: a complete producer output was captured in a separate file.

References on one result must collectively be sufficient to recover content
that its producer omitted. The policy follows one invariant:

```text
no reference  -> save the exact ToolResult once -> attach reference -> preview
has reference -> reuse that source                         -> preview
```

This prevents a spill chain. Reading an oversized saved output returns a
`SOURCE_FILE` reference to that same path. If the read window is shortened
again, the policy reuses the source instead of writing `copy-2`, `copy-3`, and
so on.

The default location is
`${java.io.tmpdir}/agent-sdk-tool-output`. The store requests owner-only
directory/file permissions on POSIX filesystems. Files are not automatically
expired in the MVP because deleting one during a Turn would make the model's
path invalid; the host application or operating-system temp policy owns
retention and cleanup.

If persistence fails, execution fails explicitly instead of silently losing
the omitted data.

## Producer behavior

- `read_file` pages the original file and attaches that source path. It never
  copies a large source merely because the page is bounded.
- `glob` keeps the sorted preview and lazily streams every match to one output
  file once the result count exceeds the preview limit.
- `bash` captures raw stdout and stderr while the process runs. If either is
  truncated, both complete streams are retained and referenced.
- `write_file`, `edit`, and Todo tools return compact status data and normally
  need no producer spill.
- custom Tools, Agent-as-Tool, and future tools are protected by the final
  policy. Producers that can emit unbounded data should stream directly to a
  `ToolOutputStore` rather than first materializing a huge `String`.

When a custom output directory is used, configure the generic policy and
workspace tools with the same path so `read_file` can inspect it:

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

Only `read_file` receives read access to this configured directory. File
mutation and Bash working-directory resolution remain workspace-scoped.

## Structured errors

An exception class name and stack trace rarely helps a model recover. Tool
failures should instead contain:

- a stable uppercase error code;
- a concise explanation of what failed;
- whether retry is meaningful;
- a concrete recovery instruction;
- small structured details such as the path or Tool name.

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

With `REPORT_TO_MODEL`, the model receives:

```text
Error [FILE_NOT_OBSERVED]: The file was not read in this Turn
Recovery: Read the file, then retry the edit.
Details:
- path: README.md
```

The same `ToolErrorInfo` remains available programmatically on `ToolResult`.
Undeclared exceptions are normalized into `INVALID_TOOL_ARGUMENTS`,
`TOOL_TIMEOUT`, `TOOL_IO_ERROR` or `TOOL_EXECUTION_FAILED`. With `FAIL_FAST`,
execution exceptions still fail the Turn.

## Design references

This design follows mature Harness behavior without copying its runtime model:

- [DeepSeek Harness filesystem tools](https://github.com/deepseek-ai/deepseek-harness/tree/master/packages/fs/tool-fs)
  bound reads by lines, per-line length and bytes, and add recovery
  instructions to stale filesystem errors.
- [OpenCode truncation](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/truncate.ts)
  uses a final 2,000-line/50-KiB Tool-output boundary and preserves a visible
  truncation notice.
- [Pi coding-agent truncation example](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/examples/extensions/truncated-tool.ts)
  writes the complete result to an OS temporary directory and returns its path
  alongside the preview.
- [Pi streaming output accumulator](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/src/core/tools/output-accumulator.ts)
  starts a bounded stream capture and persists the raw output when truncation
  occurs; its Bash Tool returns the resulting full-output path.
- [Codex output truncation](https://github.com/openai/codex/blob/main/codex-rs/utils/output-truncation/src/lib.rs)
  preserves both the beginning and end of oversized execution output and
  reports original size metadata.

The SDK keeps these ideas behind `ToolResult`, `ToolOutputStore`, and one small
policy instead of adding an event store or compaction framework to the MVP.
