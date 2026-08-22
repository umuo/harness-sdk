package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.Tools;

import java.io.IOException;
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

final class GlobTool {

    private static final Set<String> VCS_DIRECTORIES =
        new java.util.HashSet<String>(java.util.Arrays.asList(
            ".git", ".svn", ".hg", ".bzr", ".jj"
        ));

    private GlobTool() {
    }

    static Tool create(WorkspacePathResolver paths,
                       int maxResults,
                       int maxScannedEntries) {
        ToolDefinition definition = ToolDefinition.builder()
            .name("glob")
            .description(
                "Find files by glob pattern inside the workspace. A pattern "
                    + "without '/' matches file names at any depth."
            )
            .inputSchema("{\"type\":\"object\",\"properties\":{"
                + "\"pattern\":{\"type\":\"string\"},"
                + "\"path\":{\"type\":\"string\"}},"
                + "\"required\":[\"pattern\"],\"additionalProperties\":false}")
            .build();
        return Tools.sync(definition, (arguments, context) -> {
            String pattern = arguments.requireString("pattern");
            if (pattern.trim().isEmpty()) {
                throw new IllegalArgumentException("pattern must not be blank");
            }
            Path searchRoot = paths.resolveDirectory(
                arguments.optionalString("path").orElse(".")
            );
            return execute(
                paths, searchRoot, pattern, maxResults, maxScannedEntries
            );
        });
    }

    private static ToolResult execute(WorkspacePathResolver paths,
                                      Path searchRoot,
                                      String pattern,
                                      int maxResults,
                                      int maxScannedEntries) {
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
                            BasicFileAttributes attributes) {
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
                        matches.incrementAndGet();
                        String display = paths.display(file);
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
        } catch (ScanLimitException error) {
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
        } catch (IOException error) {
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
                .append(" files. Use a more specific path or pattern.)");
        }
        if (unreadable.get() > 0) {
            output.append("\n[glob warning: skipped ")
                .append(unreadable.get()).append(" unreadable paths]");
        }
        return ToolResult.success(output.toString())
            .withMetadata("count", matches.get())
            .withMetadata("returned", results.size())
            .withMetadata("truncated", truncated)
            .withMetadata("unreadable", unreadable.get())
            .withMetadata("searchRoot", paths.display(searchRoot));
    }

    private static void checkScanLimit(int scanned, int maximum) {
        if (scanned > maximum) {
            throw new ScanLimitException();
        }
    }

    private static final class ScanLimitException extends RuntimeException {
    }
}
