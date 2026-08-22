# Tool Results, Truncation and Errors

## Three output boundaries

Tool output is controlled at three different layers:

1. The Tool bounds acquisition. File reads use windows, searches cap matches,
   and processes capture bounded streams. This prevents memory exhaustion.
2. `ToolResultPolicy` is the final boundary before a result enters AgentState
   and model history. It protects the context from custom or plugin-provided
   Tools that return unexpectedly large strings.
3. History compaction may later remove old results when a long-running session
   approaches its model context limit. Compaction is not part of the first MVP.

The default `BoundedToolResultPolicy` allows at most 50 KiB and 2,000 lines. A
larger result becomes a UTF-8-safe head/tail preview containing a visible
truncation notice. Metadata records:

- `toolOutputTruncated`
- `toolOutputOriginalBytes`
- `toolOutputOriginalLines`
- `toolOutputRetainedBytes`
- `toolOutputStrategy`

Only the bounded result is appended as a Tool message and retained in the
current State. Built-in paginated Tools tell the model how to retrieve another
window. Process Tools may additionally provide a spill path when their own
capture layer has a configured output store.

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
- [Codex output truncation](https://github.com/openai/codex/blob/main/codex-rs/utils/output-truncation/src/lib.rs)
  preserves both the beginning and end of oversized execution output and
  reports original size metadata.

The SDK keeps these ideas behind one small policy instead of adding an event
store or compaction framework to the MVP.
