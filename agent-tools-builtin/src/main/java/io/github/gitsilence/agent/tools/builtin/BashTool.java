package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.runtime.Futures;
import io.github.gitsilence.agent.tool.AbstractAsyncTool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
import io.github.gitsilence.agent.tool.ToolOutputReference;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class BashTool extends AbstractAsyncTool<BashTool.Input> {

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
        super(
            "bash",
            "Run one Bash command in the workspace. No shell state persists "
                + "between calls; use workdir instead of relying on cd.",
            Input.class
        );
        this.paths = paths;
        this.executable = executable;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        this.maxTimeoutMillis = maxTimeoutMillis;
        this.maxStreamBytes = maxStreamBytes;
        this.spillDirectory = spillDirectory;
    }

    @Override
    protected CompletableFuture<ToolResult> executeAsync(Input arguments,
                                                          ToolContext context) {
        final String command = arguments.command;
        if (command.trim().isEmpty()) {
            return Futures.failed(new ToolFailureException(
                ToolErrorInfo.builder(
                    "INVALID_COMMAND", "command must be a non-empty string"
                ).retryable(true)
                    .recoveryHint("Provide a concrete Bash command and retry.")
                    .build()
            ));
        }
        final int timeout = arguments.timeoutMillis == null
            ? defaultTimeoutMillis : arguments.timeoutMillis;
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
                arguments.workdir == null ? "." : arguments.workdir
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

    static final class Input {
        @ToolParam(description = "Bash command to run")
        public String command;

        @ToolParam(
            description = "Working directory relative to the workspace",
            required = false
        )
        public String workdir;

        @ToolParam(
            name = "timeout_ms",
            description = "Positive command timeout in milliseconds",
            required = false
        )
        public Integer timeoutMillis;
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
            try {
                stdout = new BoundedProcessOutput(
                    process.getInputStream(), maxStreamBytes,
                    spillDirectory, prefix + "stdout-"
                );
                stderr = new BoundedProcessOutput(
                    process.getErrorStream(), maxStreamBytes,
                    spillDirectory, prefix + "stderr-"
                );
            } catch (IOException preservationError) {
                if (stdout != null) stdout.discard();
                destroy(process);
                throw new ToolFailureException(
                    ToolErrorInfo.builder(
                        "OUTPUT_PRESERVATION_FAILED",
                        "Cannot prepare complete Bash output capture: "
                            + preservationError.getMessage()
                    ).retryable(false)
                        .recoveryHint(
                            "Check the configured tool output directory and its permissions."
                        )
                        .detail("outputDirectory", spillDirectory)
                        .build(),
                    preservationError
                );
            }
            stdoutThread = daemon("agent-bash-stdout", stdout);
            stderrThread = daemon("agent-bash-stderr", stderr);
            stdoutThread.start();
            stderrThread.start();

            boolean completed = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!completed) {
                destroy(process);
                process.waitFor(1000L, TimeUnit.MILLISECONDS);
            }
            boolean stdoutDrained = join(stdoutThread, stdout);
            boolean stderrDrained = join(stderrThread, stderr);
            if (!stdoutDrained || !stderrDrained) {
                String failedStream = !stdoutDrained ? "stdout" : "stderr";
                discard(stdout, stderr);
                throw new ToolFailureException(
                    ToolErrorInfo.builder(
                        "OUTPUT_CAPTURE_FAILED",
                        "Bash " + failedStream + " did not finish draining"
                    ).retryable(false)
                        .recoveryHint(
                            "Ensure the command and its child processes close inherited output streams."
                        )
                        .detail("stream", failedStream)
                        .build()
                );
            }
            IOException captureError = stdout.getError() != null
                ? stdout.getError() : stderr.getError();
            if (captureError != null) {
                String failedStream = stdout.getError() != null
                    ? "stdout" : "stderr";
                discard(stdout, stderr);
                throw new ToolFailureException(
                    ToolErrorInfo.builder(
                        "OUTPUT_CAPTURE_FAILED",
                        "Cannot capture Bash " + failedStream + ": "
                            + captureError.getMessage()
                    ).retryable(false)
                        .recoveryHint(
                            "Check available disk space and tool output directory permissions."
                        )
                        .detail("stream", failedStream)
                        .detail("outputDirectory", spillDirectory)
                        .build(),
                    captureError
                );
            }
            if (result.isCancelled()) {
                discard(stdout, stderr);
                return;
            }
            boolean retainStreams = stdout.isTruncated() || stderr.isTruncated();
            stdout.finalizeRetention(retainStreams);
            stderr.finalizeRetention(retainStreams);
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
            if (!result.complete(toolResult)) {
                discard(stdout, stderr);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            destroy(process);
            discard(stdout, stderr);
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
            discard(stdout, stderr);
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
            discard(stdout, stderr);
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
            String fullOutput = stdout.retainedSpillPath();
            enriched = enriched.withMetadata(
                "stdoutSpillPath", fullOutput
            ).withOutputReference(ToolOutputReference.temporaryFile(
                java.nio.file.Paths.get(fullOutput),
                "complete stdout; read_file offset/limit"
            ));
        }
        if (stderr.retainedSpillPath() != null) {
            String fullOutput = stderr.retainedSpillPath();
            enriched = enriched.withMetadata(
                "stderrSpillPath", fullOutput
            ).withOutputReference(ToolOutputReference.temporaryFile(
                java.nio.file.Paths.get(fullOutput),
                "complete stderr; read_file offset/limit"
            ));
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

    private static boolean join(Thread thread,
                                BoundedProcessOutput output)
            throws InterruptedException {
        thread.join(2000L);
        if (thread.isAlive()) {
            output.closeInput();
            thread.join(500L);
        }
        return !thread.isAlive();
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

    private static void discard(BoundedProcessOutput stdout,
                                BoundedProcessOutput stderr) {
        if (stdout != null) stdout.discard();
        if (stderr != null) stderr.discard();
    }
}
