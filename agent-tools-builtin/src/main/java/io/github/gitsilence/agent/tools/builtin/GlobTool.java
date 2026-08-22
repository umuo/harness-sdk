package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.AbstractTool;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
import io.github.gitsilence.agent.tool.ToolOutputReference;
import io.github.gitsilence.agent.tool.ToolOutputStore;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.FileVisitOption;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.PatternSyntaxException;

final class GlobTool extends AbstractTool<GlobTool.Input> {

    private static final Set<String> VCS_DIRECTORIES =
        new java.util.HashSet<String>(java.util.Arrays.asList(
            ".git", ".svn", ".hg", ".bzr", ".jj"
        ));

    private final WorkspacePathResolver paths;
    private final int maxResults;
    private final int maxScannedEntries;
    private final ToolOutputStore outputStore;

    static Tool create(WorkspacePathResolver paths,
                       int maxResults,
                       int maxScannedEntries,
                       ToolOutputStore outputStore) {
        return new GlobTool(
            paths, maxResults, maxScannedEntries, outputStore
        );
    }

    private GlobTool(WorkspacePathResolver paths,
                     int maxResults,
                     int maxScannedEntries,
                     ToolOutputStore outputStore) {
        super(
            "glob",
            "Find files by glob pattern inside the workspace. A pattern "
                + "without '/' matches file names at any depth.",
            Input.class
        );
        this.paths = paths;
        this.maxResults = maxResults;
        this.maxScannedEntries = maxScannedEntries;
        this.outputStore = outputStore;
    }

    @Override
    protected ToolResult execute(Input arguments, ToolContext context) {
        String pattern = arguments.pattern;
        if (pattern.trim().isEmpty()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        Path searchRoot = paths.resolveDirectory(
            arguments.path == null ? "." : arguments.path
        );
        return execute(
            paths, searchRoot, pattern, maxResults, maxScannedEntries,
            outputStore
        );
    }

    static final class Input {
        @ToolParam(description = "Glob pattern, for example **/*.java")
        public String pattern;

        @ToolParam(description = "Directory to search, relative to the workspace", required = false)
        public String path;
    }

    private static ToolResult execute(WorkspacePathResolver paths,
                                      Path searchRoot,
                                      String pattern,
                                      int maxResults,
                                      int maxScannedEntries,
                                      ToolOutputStore outputStore) {
        final PathMatcher matcher;
        final PathMatcher rootFallback;
        try {
            FileSystem fileSystem = searchRoot.getFileSystem();
            String platformPattern = pattern.replace(
                "/", fileSystem.getSeparator()
            );
            matcher = fileSystem.getPathMatcher("glob:" + platformPattern);
            rootFallback = platformPattern.startsWith(
                "**" + fileSystem.getSeparator()
            )
                ? fileSystem.getPathMatcher(
                    "glob:" + platformPattern.substring(
                        3 * fileSystem.getSeparator().length()
                    )
                )
                : null;
        } catch (PatternSyntaxException error) {
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "GLOB_INVALID_PATTERN",
                    "Invalid glob pattern '" + pattern + "': " + error.getMessage()
                ).retryable(true)
                    .recoveryHint("Correct the glob syntax and retry.")
                    .detail("pattern", pattern)
                    .build(),
                error
            );
        }
        final boolean matchBasename = pattern.indexOf('/') < 0
            && pattern.indexOf('\\') < 0;
        final PriorityQueue<String> retained = new PriorityQueue<String>(
            maxResults + 1, Collections.reverseOrder()
        );
        final AtomicInteger matches = new AtomicInteger();
        final AtomicInteger scanned = new AtomicInteger();
        final AtomicInteger unreadable = new AtomicInteger();
        final FullGlobOutput fullOutput = new FullGlobOutput(
            outputStore, maxResults
        );
        try {
            Files.walkFileTree(
                searchRoot,
                EnumSet.noneOf(FileVisitOption.class),
                Integer.MAX_VALUE,
                new FileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory,
                            BasicFileAttributes attributes) {
                        if (!directory.equals(searchRoot)
                                && VCS_DIRECTORIES.contains(
                                    directory.getFileName().toString()
                                )) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        checkScanLimit(scanned.incrementAndGet(), maxScannedEntries);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attributes) throws IOException {
                        checkScanLimit(scanned.incrementAndGet(), maxScannedEntries);
                        if (!attributes.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path relative = searchRoot.relativize(file);
                        Path candidate = matchBasename ? file.getFileName() : relative;
                        if (!matcher.matches(candidate)
                                && (rootFallback == null
                                    || !rootFallback.matches(candidate))) {
                            return FileVisitResult.CONTINUE;
                        }
                        int matchNumber = matches.incrementAndGet();
                        String display = paths.display(file);
                        fullOutput.record(display, matchNumber);
                        retained.offer(display);
                        if (retained.size() > maxResults) {
                            retained.poll();
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        unreadable.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                            Path directory,
                            IOException error) throws IOException {
                        if (error != null && directory.equals(searchRoot)) {
                            throw error;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                }
            );
            fullOutput.finish();
        } catch (ScanLimitException error) {
            fullOutput.discard();
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "GLOB_SCAN_LIMIT",
                    "Glob scanned more than " + maxScannedEntries
                        + " filesystem entries"
                ).retryable(true)
                    .recoveryHint(
                        "Use a more specific path or pattern to narrow the search."
                    )
                    .detail("pattern", pattern)
                    .detail("path", paths.display(searchRoot))
                    .build(),
                error
            );
        } catch (OutputPreservationException error) {
            fullOutput.discard();
            Throwable cause = error.getCause() == null
                ? error : error.getCause();
            throw new ToolFailureException(
                ToolErrorInfo.builder(
                    "OUTPUT_PRESERVATION_FAILED",
                    "Cannot preserve complete glob output: "
                        + cause.getMessage()
                ).retryable(false)
                    .recoveryHint(
                        "Check available disk space and tool output directory permissions."
                    )
                    .detail("outputDirectory", outputStore.getDirectory())
                    .build(),
                cause
            );
        } catch (IOException error) {
            fullOutput.discard();
            throw BuiltinToolErrors.io("search", searchRoot, error);
        }

        List<String> results = new ArrayList<String>(retained);
        Collections.sort(results);
        if (results.isEmpty()) {
            return ToolResult.success("No files found")
                .withMetadata("count", 0)
                .withMetadata("truncated", false);
        }
        StringBuilder output = new StringBuilder();
        for (String result : results) {
            if (output.length() > 0) output.append('\n');
            output.append(result);
        }
        boolean truncated = matches.get() > results.size();
        if (truncated) {
            output.append("\n\n(Results truncated: showing ")
                .append(results.size()).append(" of ").append(matches.get())
                .append(" files. Full output: ")
                .append(fullOutput.getPath())
                .append("; use read_file with offset and limit.)");
        }
        if (unreadable.get() > 0) {
            output.append("\n[glob warning: skipped ")
                .append(unreadable.get()).append(" unreadable paths]");
        }
        ToolResult result = ToolResult.success(output.toString())
            .withMetadata("count", matches.get())
            .withMetadata("returned", results.size())
            .withMetadata("truncated", truncated)
            .withMetadata("unreadable", unreadable.get())
            .withMetadata("searchRoot", paths.display(searchRoot));
        if (truncated) {
            result = result
                .withMetadata("fullOutputPath", fullOutput.getPath().toString())
                .withOutputReference(ToolOutputReference.temporaryFile(
                    fullOutput.getPath(),
                    "all glob matches; read_file offset/limit"
                ));
        }
        return result;
    }

    private static void checkScanLimit(int scanned, int maximum) {
        if (scanned > maximum) {
            throw new ScanLimitException();
        }
    }

    private static final class ScanLimitException extends RuntimeException {
    }

    /** Lazily starts a complete traversal-order capture when the preview overflows. */
    private static final class FullGlobOutput {
        private final ToolOutputStore outputStore;
        private final int threshold;
        private final List<String> initialValues = new ArrayList<String>();
        private Path path;
        private BufferedWriter writer;

        private FullGlobOutput(ToolOutputStore outputStore, int threshold) {
            this.outputStore = outputStore;
            this.threshold = threshold;
        }

        private void record(String value, int matchNumber) throws IOException {
            try {
                if (matchNumber <= threshold) {
                    initialValues.add(value);
                    return;
                }
                if (writer == null) {
                    path = outputStore.createFile("glob-output-", ".txt");
                    writer = Files.newBufferedWriter(
                        path, StandardCharsets.UTF_8
                    );
                    for (String earlier : initialValues) {
                        writeLine(earlier);
                    }
                    initialValues.clear();
                }
                writeLine(value);
            } catch (IOException error) {
                discard();
                throw new OutputPreservationException(error);
            }
        }

        private void writeLine(String value) throws IOException {
            writer.write(value);
            writer.newLine();
        }

        private void finish() throws IOException {
            if (writer != null) {
                try {
                    writer.close();
                    writer = null;
                } catch (IOException error) {
                    discard();
                    throw new OutputPreservationException(error);
                }
            }
        }

        private void discard() {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Best effort while abandoning an incomplete traversal.
                }
                writer = null;
            }
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup for incomplete output.
                }
                path = null;
            }
            initialValues.clear();
        }

        private Path getPath() {
            return path;
        }
    }

    private static final class OutputPreservationException extends IOException {
        private OutputPreservationException(IOException cause) {
            super(cause);
        }
    }
}
