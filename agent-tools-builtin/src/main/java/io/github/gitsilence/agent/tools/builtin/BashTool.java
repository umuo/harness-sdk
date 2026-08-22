package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.runtime.Futures;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolArguments;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
import io.github.gitsilence.agent.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class BashTool implements Tool {

    private final ToolDefinition definition;
    private final WorkspacePathResolver paths;
    private final String executable;
    private final int defaultTimeoutMillis;
    private final int maxTimeoutMillis;
    private final int maxStreamBytes;
    private final Path spillDirectory;

    BashTool(WorkspacePathResolver paths,
             String executable,
             int defaultTimeoutMillis,
             int maxTimeoutMillis,
             int maxStreamBytes,
             Path spillDirectory) {
        this.paths = paths;
        this.executable = executable;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        this.maxTimeoutMillis = maxTimeoutMillis;
        this.maxStreamBytes = maxStreamBytes;
        this.spillDirectory = spillDirectory;
        this.definition = ToolDefinition.builder()
            .name("bash")
            .description(
                "Run one Bash command in the workspace. No shell state persists "
                    + "between calls; use workdir instead of relying on cd."
            )
            .inputSchema("{\"type\":\"object\",\"properties\":{"
                + "\"command\":{\"type\":\"string\"},"
                + "\"workdir\":{\"type\":\"string\"},"
                + "\"timeout_ms\":{\"type\":\"integer\",\"minimum\":1}},"
                + "\"required\":[\"command\"],\"additionalProperties\":false}")
            .build();
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolArguments arguments,
                                                  ToolContext context) {
        final String command = arguments.requireString("command");
        if (command.trim().isEmpty()) {
            return Futures.failed(new ToolFailureException(
                ToolErrorInfo.builder(
                    "INVALID_COMMAND", "command must be a non-empty string"
                ).retryable(true)
                    .recoveryHint("Provide a concrete Bash command and retry.")
                    .build()
            ));
        }
        final int timeout = arguments.optionalInt("timeout_ms")
            .orElse(defaultTimeoutMillis);
        if (timeout < 1 || timeout > maxTimeoutMillis) {
            return Futures.failed(new ToolFailureException(
                ToolErrorInfo.builder(
                    "INVALID_COMMAND_TIMEOUT",
                    "timeout_ms must be between 1 and " + maxTimeoutMillis
                ).retryable(true)
                    .recoveryHint("Choose a timeout inside the advertised range.")
                    .detail("timeout_ms", timeout)
                    .build()
            ));
        }
        final Path workdir;
        try {
            workdir = paths.resolveDirectory(
                arguments.optionalString("workdir").orElse(".")
            );
        } catch (Throwable error) {
            return Futures.failed(error);
        }

        final CompletableFuture<ToolResult> result =
            new CompletableFuture<ToolResult>();
        final AtomicReference<Process> active = new AtomicReference<Process>();
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                destroy(active.getAndSet(null));
            }
        });
        try {
            context.getExecutor().execute(() -> runCommand(
                command, workdir, timeout, context, result, active
            ));
        } catch (Throwable rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private void runCommand(String command,
                            Path workdir,
                            int timeout,
                            ToolContext context,
                            CompletableFuture<ToolResult> result,
                            AtomicReference<Process> active) {
        Process process = null;
        BoundedProcessOutput stdout = null;
        BoundedProcessOutput stderr = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                Arrays.asList(executable, "-c", command)
            );
            builder.directory(workdir.toFile());
            process = builder.start();
            active.set(process);
            if (result.isCancelled()) {
                destroy(process);
                return;
            }
            String prefix = "bash-" + context.getTurnId() + "-";
            stdout = new BoundedProcessOutput(
                process.getInputStream(), maxStreamBytes,
                spillDirectory, prefix + "stdout-"
            );
            stderr = new BoundedProcessOutput(
                process.getErrorStream(), maxStreamBytes,
                spillDirectory, prefix + "stderr-"
            );
            stdoutThread = daemon("agent-bash-stdout", stdout);
            stderrThread = daemon("agent-bash-stderr", stderr);
            stdoutThread.start();
            stderrThread.start();

            boolean completed = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!completed) {
                destroy(process);
                process.waitFor(1000L, TimeUnit.MILLISECONDS);
            }
            join(stdoutThread, stdout);
            join(stderrThread, stderr);
            if (stdout.getError() != null) {
                throw stdout.getError();
            }
            if (stderr.getError() != null) {
                throw stderr.getError();
            }
            int exitCode = completed ? process.exitValue() : safeExitValue(process);
            String rendered = render(stdout, stderr, completed, timeout, exitCode);
            ToolResult toolResult;
            if (!completed) {
                ToolErrorInfo timeoutError = ToolErrorInfo.builder(
                        "COMMAND_TIMED_OUT",
                        "Command timed out after " + timeout + "ms"
                    ).retryable(true)
                        .recoveryHint(
                            "Inspect partial output, narrow the command, or choose a valid longer timeout."
                        )
                        .detail("workdir", paths.display(workdir))
                        .build();
                toolResult = ToolResult.failure(
                    rendered + "\n" + timeoutError.toModelMessage(),
                    timeoutError
                );
            } else if (exitCode != 0) {
                ToolErrorInfo exitError = ToolErrorInfo.builder(
                        "COMMAND_EXIT_NON_ZERO",
                        "Command exited with code " + exitCode
                    ).retryable(true)
                        .recoveryHint(
                            "Inspect stdout and stderr, correct the command or inputs, then retry."
                        )
                        .detail("exitCode", exitCode)
                        .detail("workdir", paths.display(workdir))
                        .build();
                toolResult = ToolResult.failure(
                    rendered + "\n" + exitError.toModelMessage(),
                    exitError
                );
            } else {
                toolResult = ToolResult.success(rendered);
            }
            toolResult = addMetadata(toolResult, stdout, stderr, completed, exitCode, workdir);
            result.complete(toolResult);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            destroy(process);
            result.completeExceptionally(new ToolFailureException(
                ToolErrorInfo.builder(
                    "COMMAND_INTERRUPTED", "Command execution was interrupted"
                ).retryable(true)
                    .recoveryHint("Retry only if the work is still required.")
                    .build(),
                interrupted
            ));
        } catch (IOException error) {
            destroy(process);
            result.completeExceptionally(new ToolFailureException(
                ToolErrorInfo.builder(
                    "COMMAND_START_FAILED",
                    "Cannot run Bash command: " + error.getMessage()
                ).retryable(false)
                    .recoveryHint(
                        "Check that the configured Bash executable and workdir are available."
                    )
                    .detail("executable", executable)
                    .detail("workdir", paths.display(workdir))
                    .build(),
                error
            ));
        } catch (Throwable error) {
            destroy(process);
            result.completeExceptionally(error);
        } finally {
            active.compareAndSet(process, null);
        }
    }

    private ToolResult addMetadata(ToolResult result,
                                   BoundedProcessOutput stdout,
                                   BoundedProcessOutput stderr,
                                   boolean completed,
                                   int exitCode,
                                   Path workdir) {
        ToolResult enriched = result
            .withMetadata("workdir", paths.display(workdir))
            .withMetadata("timedOut", !completed)
            .withMetadata("exitCode", exitCode)
            .withMetadata("stdoutBytes", stdout.getTotalBytes())
            .withMetadata("stderrBytes", stderr.getTotalBytes())
            .withMetadata("stdoutTruncated", stdout.isTruncated())
            .withMetadata("stderrTruncated", stderr.isTruncated());
        if (stdout.retainedSpillPath() != null) {
            enriched = enriched.withMetadata(
                "stdoutSpillPath", stdout.retainedSpillPath()
            );
        }
        if (stderr.retainedSpillPath() != null) {
            enriched = enriched.withMetadata(
                "stderrSpillPath", stderr.retainedSpillPath()
            );
        }
        return enriched;
    }

    private static String render(BoundedProcessOutput stdout,
                                 BoundedProcessOutput stderr,
                                 boolean completed,
                                 int timeout,
                                 int exitCode) {
        String out = stdout.text();
        String err = stderr.text();
        StringBuilder result = new StringBuilder();
        if (out.isEmpty() && err.isEmpty()) {
            result.append("(no output)");
        } else {
            result.append(out);
            if (!err.isEmpty()) {
                if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
                    result.append('\n');
                }
                result.append("[stderr]\n").append(err);
            }
        }
        appendTruncation(result, "stdout", stdout);
        appendTruncation(result, "stderr", stderr);
        if (!completed) {
            appendLine(result, "[timed out after " + timeout + "ms]");
        }
        appendLine(result, "[exit code: " + exitCode + "]");
        return result.toString();
    }

    private static void appendTruncation(StringBuilder result,
                                         String stream,
                                         BoundedProcessOutput output) {
        if (!output.isTruncated()) return;
        String path = output.retainedSpillPath();
        appendLine(
            result,
            "[" + stream + " truncated; full output: "
                + (path == null ? "(unavailable)" : path) + "]"
        );
    }

    private static void appendLine(StringBuilder result, String line) {
        if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
            result.append('\n');
        }
        result.append(line);
    }

    private static Thread daemon(String name, Runnable target) {
        Thread thread = new Thread(target, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread,
                             BoundedProcessOutput output) throws InterruptedException {
        thread.join(2000L);
        if (thread.isAlive()) {
            output.closeInput();
            thread.join(500L);
        }
    }

    private static int safeExitValue(Process process) {
        if (process == null || process.isAlive()) return -1;
        return process.exitValue();
    }

    private static void destroy(Process process) {
        if (process == null) return;
        try {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (Throwable ignored) {
            // Best effort on Java 8; process-tree isolation belongs to a sandbox runtime.
        }
    }
}
