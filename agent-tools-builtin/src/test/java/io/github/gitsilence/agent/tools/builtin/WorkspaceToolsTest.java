package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.tool.BoundedToolResultPolicy;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;
import io.github.gitsilence.agent.tool.ToolOutputReference;
import io.github.gitsilence.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceToolsTest {

    @TempDir
    Path temporary;

    @Test
    void typedSchemasKeepStableModelFacingArgumentNames() throws Exception {
        WorkspaceTools tools = WorkspaceTools.builder(workspace())
            .enableBash(true)
            .build();

        String readSchema = tools.getReadFile().definition().getInputSchema();
        assertTrue(readSchema.contains("\"file_path\""));
        assertFalse(readSchema.contains("\"filePath\""));
        assertTrue(readSchema.contains("First 1-based line"));
        assertTrue(readSchema.contains("\"required\":[\"file_path\"]"));

        String bashSchema = tools.getBash().get().definition().getInputSchema();
        assertTrue(bashSchema.contains("\"timeout_ms\""));
        assertFalse(bashSchema.contains("\"timeoutMillis\""));

        String patchSchema = tools.getApplyPatch().definition().getInputSchema();
        assertTrue(patchSchema.contains("\"patch\""));
        assertTrue(patchSchema.contains("\"required\":[\"patch\"]"));
        assertTrue(tools.getTools().contains(tools.getApplyPatch()));
    }

    @Test
    void readFileReturnsNumberedPageAndContinuationHint() throws Exception {
        Path root = workspace();
        Files.write(
            root.resolve("notes.txt"),
            "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8)
        );
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .readLimit(2)
            .build();
        ScriptedModel model = new ScriptedModel(
            tool("read-1", "read_file",
                "{\"file_path\":\"notes.txt\",\"offset\":2,\"limit\":2}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("read notes");

        String content = result.getState().getToolResults().get(0)
            .getResult().getContent();
        assertTrue(content.contains("2: two"));
        assertTrue(content.contains("3: three"));
        assertTrue(content.contains("Use offset=4 to continue"));
        assertEquals(4, result.getState().getToolResults().get(0)
            .getResult().getMetadata().get("totalLines"));
    }

    @Test
    void overwriteFailsUntilFileWasReadInSameTurn() throws Exception {
        Path root = workspace();
        Path file = root.resolve("config.txt");
        Files.write(file, "old".getBytes(StandardCharsets.UTF_8));
        WorkspaceTools tools = WorkspaceTools.builder(root).build();
        ScriptedModel model = new ScriptedModel(
            tool("write-1", "write_file",
                "{\"file_path\":\"config.txt\",\"content\":\"new\"}"),
            tool("read-1", "read_file",
                "{\"file_path\":\"config.txt\"}"),
            tool("write-2", "write_file",
                "{\"file_path\":\"config.txt\",\"content\":\"new\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("update config");

        List<ToolExecutionRecord> records = result.getState().getToolResults();
        assertEquals("FILE_NOT_OBSERVED", records.get(0).getResult()
            .getErrorInfo().getCode());
        assertTrue(records.get(0).getResult().getContent()
            .contains("Read the file, then retry"));
        assertFalse(records.get(1).getResult().isError());
        assertFalse(records.get(2).getResult().isError());
        assertEquals("new", new String(
            Files.readAllBytes(file), StandardCharsets.UTF_8
        ));
    }

    @Test
    void editRejectsFileChangedAfterRead() throws Exception {
        Path root = workspace();
        Path file = root.resolve("source.txt");
        Files.write(file, "before".getBytes(StandardCharsets.UTF_8));
        WorkspaceTools tools = WorkspaceTools.builder(root).build();
        AtomicInteger round = new AtomicInteger();
        ChatModel model = request -> {
            int current = round.incrementAndGet();
            if (current == 1) {
                return completed(tool(
                    "read-1", "read_file", "{\"file_path\":\"source.txt\"}"
                ));
            }
            if (current == 2) {
                try {
                    Files.write(file, "external".getBytes(StandardCharsets.UTF_8));
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
                return completed(tool(
                    "edit-1", "edit",
                    "{\"file_path\":\"source.txt\","
                        + "\"old_string\":\"before\",\"new_string\":\"after\"}"
                ));
            }
            return completed(finalAnswer("stopped safely"));
        };

        AgentResult result = agent(tools, model).run("edit source");

        assertEquals("FILE_CHANGED_SINCE_READ", result.getState()
            .getToolResults().get(1).getResult().getErrorInfo().getCode());
        assertEquals("external", new String(
            Files.readAllBytes(file), StandardCharsets.UTF_8
        ));
    }

    @Test
    void editRequiresUniqueTextUnlessReplaceAllIsExplicit() throws Exception {
        Path root = workspace();
        Path file = root.resolve("repeat.txt");
        Files.write(file, "same\nsame\n".getBytes(StandardCharsets.UTF_8));
        WorkspaceTools tools = WorkspaceTools.builder(root).build();
        ScriptedModel model = new ScriptedModel(
            tool("read-1", "read_file",
                "{\"file_path\":\"repeat.txt\"}"),
            tool("edit-1", "edit",
                "{\"file_path\":\"repeat.txt\",\"old_string\":\"same\","
                    + "\"new_string\":\"changed\"}"),
            tool("edit-2", "edit",
                "{\"file_path\":\"repeat.txt\",\"old_string\":\"same\","
                    + "\"new_string\":\"changed\",\"replace_all\":true}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("edit repeats");

        assertEquals("EDIT_TEXT_NOT_UNIQUE", result.getState()
            .getToolResults().get(1).getResult().getErrorInfo().getCode());
        assertEquals(2, result.getState().getToolResults().get(2)
            .getResult().getMetadata().get("replacements"));
        assertEquals("changed\nchanged\n", new String(
            Files.readAllBytes(file), StandardCharsets.UTF_8
        ));
    }

    @Test
    void globIsBoundedSortedAndSkipsVcsMetadata() throws Exception {
        Path root = workspace();
        Path outputDirectory = temporary.resolve("glob-output");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".git"));
        Files.write(root.resolve("z.java"), new byte[0]);
        Files.write(root.resolve("a.java"), new byte[0]);
        Files.write(root.resolve("src/b.java"), new byte[0]);
        Files.write(root.resolve(".git/hidden.java"), new byte[0]);
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .globMaxResults(2)
            .toolOutputDirectory(outputDirectory)
            .build();
        ScriptedModel model = new ScriptedModel(
            tool("glob-1", "glob", "{\"pattern\":\"*.java\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("find java");

        ToolExecutionRecord record = result.getState().getToolResults().get(0);
        String output = record.getResult().getContent();
        assertTrue(output.startsWith("a.java\nsrc/b.java"));
        assertTrue(output.contains("showing 2 of 3 files"));
        assertFalse(output.contains("hidden.java"));
        assertEquals(1, record.getResult().getOutputReferences().size());
        ToolOutputReference reference = record.getResult()
            .getOutputReferences().get(0);
        assertEquals(ToolOutputReference.Kind.TEMPORARY_FILE, reference.getKind());
        String complete = new String(
            Files.readAllBytes(java.nio.file.Paths.get(reference.getPath())),
            StandardCharsets.UTF_8
        );
        assertTrue(complete.contains("a.java"));
        assertTrue(complete.contains("src/b.java"));
        assertTrue(complete.contains("z.java"));
        assertFalse(complete.contains("hidden.java"));
    }

    @Test
    void readRejectsPathOutsideWorkspace() throws Exception {
        Path root = workspace();
        Path outside = temporary.resolve("outside.txt");
        Files.write(outside, "secret".getBytes(StandardCharsets.UTF_8));
        WorkspaceTools tools = WorkspaceTools.builder(root).build();
        ScriptedModel model = new ScriptedModel(
            tool("read-1", "read_file",
                "{\"file_path\":\"" + json(outside.toString()) + "\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("read outside");

        assertEquals("PATH_OUTSIDE_WORKSPACE", result.getState()
            .getToolResults().get(0).getResult().getErrorInfo().getCode());
    }

    @Test
    void readPagesToolOutputWithoutCreatingASecondTemporaryCopy() throws Exception {
        Path root = workspace();
        Path outputDirectory = Files.createDirectories(
            temporary.resolve("readable-tool-output")
        );
        Path completeOutput = outputDirectory.resolve("command.log");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            content.append("line-").append(i).append('-')
                .append("abcdefghijklmnopqrstuvwxyz").append('\n');
        }
        Files.write(
            completeOutput,
            content.toString().getBytes(StandardCharsets.UTF_8)
        );
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .toolOutputDirectory(outputDirectory)
            .readLimit(20)
            .readMaxLineLength(50)
            .readMaxBytes(512)
            .build();
        ScriptedModel model = new ScriptedModel(
            tool("read-1", "read_file",
                "{\"file_path\":\"" + json(completeOutput.toString())
                    + "\",\"limit\":20}"),
            finalAnswer("done")
        );
        Agent agent = Agent.builder()
            .name("coding-agent")
            .description("coding-agent")
            .model(model)
            .instructions(tools.getInstructions())
            .tools(tools.getTools())
            .toolResultPolicy(new BoundedToolResultPolicy(
                256, 5, outputDirectory
            ))
            .build();

        AgentResult result = agent.run("inspect complete output");

        ToolResult read = result.getState().getToolResults().get(0).getResult();
        assertFalse(read.isError());
        assertEquals("existing_reference",
            read.getMetadata().get("toolOutputPreservation"));
        assertEquals(ToolOutputReference.Kind.SOURCE_FILE,
            read.getOutputReferences().get(0).getKind());
        try (java.util.stream.Stream<Path> files = Files.list(outputDirectory)) {
            assertEquals(1L, files.count());
        }
        assertEquals(content.toString(), new String(
            Files.readAllBytes(completeOutput), StandardCharsets.UTF_8
        ));
    }

    @Test
    void toolOutputDirectoryIsReadableButNotWritableOutsideWorkspace()
            throws Exception {
        Path root = workspace();
        Path outputDirectory = Files.createDirectories(
            temporary.resolve("read-only-tool-output")
        );
        Path completeOutput = outputDirectory.resolve("command.log");
        Files.write(
            completeOutput, "original\n".getBytes(StandardCharsets.UTF_8)
        );
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .toolOutputDirectory(outputDirectory)
            .build();
        ScriptedModel model = new ScriptedModel(
            tool("read-1", "read_file",
                "{\"file_path\":\"" + json(completeOutput.toString()) + "\"}"),
            tool("write-1", "write_file",
                "{\"file_path\":\"" + json(completeOutput.toString())
                    + "\",\"content\":\"changed\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("inspect but do not mutate");

        assertFalse(result.getState().getToolResults().get(0)
            .getResult().isError());
        assertEquals("PATH_OUTSIDE_WORKSPACE", result.getState()
            .getToolResults().get(1).getResult().getErrorInfo().getCode());
        assertEquals("original\n", new String(
            Files.readAllBytes(completeOutput), StandardCharsets.UTF_8
        ));
    }

    @Test
    void readRejectsSymlinkThatEscapesWorkspace() throws Exception {
        Path root = workspace();
        Path outside = temporary.resolve("outside-target.txt");
        Files.write(outside, "secret".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | IOException error) {
            Assumptions.assumeTrue(false, "symbolic links unavailable");
        }
        WorkspaceTools tools = WorkspaceTools.builder(root).build();
        ScriptedModel model = new ScriptedModel(
            tool("read-1", "read_file",
                "{\"file_path\":\"link.txt\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("read link");

        assertEquals("PATH_OUTSIDE_WORKSPACE", result.getState()
            .getToolResults().get(0).getResult().getErrorInfo().getCode());
    }

    @Test
    void readMarksLongLinesAndRejectsBinaryContent() throws Exception {
        Path root = workspace();
        Files.write(root.resolve("long.txt"),
            "123456789012345\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("binary.dat"), new byte[] { 1, 0, 2, 3 });
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .readMaxLineLength(10)
            .build();
        ScriptedModel model = new ScriptedModel(
            tool("read-1", "read_file",
                "{\"file_path\":\"long.txt\"}"),
            tool("read-2", "read_file",
                "{\"file_path\":\"binary.dat\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("read files");

        assertTrue(result.getState().getToolResults().get(0).getResult()
            .getContent().contains("line truncated to 10 chars"));
        assertEquals("BINARY_FILE", result.getState().getToolResults().get(1)
            .getResult().getErrorInfo().getCode());
    }

    @Test
    void bashPreservesDiagnosticsAndSpillsTruncatedStreams() throws Exception {
        Path root = workspace();
        Path outputDirectory = temporary.resolve("bash-output");
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .enableBash(true)
            .bashMaxStreamBytes(256)
            .toolOutputDirectory(outputDirectory)
            .build();
        String command = "printf 'HEAD-'; "
            + "for i in {1..1000}; do printf x; done; "
            + "printf '%s\\n' '-TAIL'; printf 'bad input\\n' >&2; exit 7";
        ScriptedModel model = new ScriptedModel(
            tool("bash-1", "bash",
                "{\"command\":\"" + json(command) + "\"}"),
            finalAnswer("handled")
        );

        AgentResult result = agent(tools, model).run("run command");

        ToolExecutionRecord record = result.getState().getToolResults().get(0);
        String output = record.getResult().getContent();
        assertEquals("COMMAND_EXIT_NON_ZERO", record.getResult()
            .getErrorInfo().getCode());
        assertTrue(output.contains("HEAD-"));
        assertTrue(output.contains("-TAIL"));
        assertTrue(output.contains("[stderr]\nbad input"));
        assertTrue(output.contains("[exit code: 7]"));
        assertTrue(output.contains("Recovery: Inspect stdout and stderr"));
        assertEquals(true, record.getResult().getMetadata().get("stdoutTruncated"));
        String spill = (String) record.getResult().getMetadata().get("stdoutSpillPath");
        assertNotNull(spill);
        assertTrue(Files.exists(java.nio.file.Paths.get(spill)));
        assertTrue(java.nio.file.Paths.get(spill).startsWith(
            outputDirectory.toRealPath()
        ));
        assertEquals(2, record.getResult().getOutputReferences().size());
        for (ToolOutputReference reference
                : record.getResult().getOutputReferences()) {
            assertEquals(ToolOutputReference.Kind.TEMPORARY_FILE,
                reference.getKind());
        }
        String complete = new String(
            Files.readAllBytes(java.nio.file.Paths.get(spill)),
            StandardCharsets.UTF_8
        );
        assertTrue(complete.startsWith("HEAD-"));
        assertTrue(complete.endsWith("-TAIL\n"));
        String stderrSpill = (String) record.getResult().getMetadata()
            .get("stderrSpillPath");
        assertEquals("bad input\n", new String(
            Files.readAllBytes(java.nio.file.Paths.get(stderrSpill)),
            StandardCharsets.UTF_8
        ));
    }

    @Test
    void bashTimeoutReturnsPartialResultWithRecovery() throws Exception {
        Path root = workspace();
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .enableBash(true)
            .bashDefaultTimeoutMillis(50)
            .bashMaxTimeoutMillis(1000)
            .build();
        ScriptedModel model = new ScriptedModel(
            tool("bash-1", "bash", "{\"command\":\"sleep 1\"}"),
            finalAnswer("handled")
        );

        AgentResult result = agent(tools, model).run("run timeout");

        ToolExecutionRecord record = result.getState().getToolResults().get(0);
        assertEquals("COMMAND_TIMED_OUT", record.getResult()
            .getErrorInfo().getCode());
        assertTrue(record.getResult().getContent().contains("timed out after 50ms"));
    }

    @Test
    void applyPatchHandlesMultipleFilesChunksAndMove() throws Exception {
        Path root = workspace();
        Files.createDirectories(root.resolve("old"));
        Files.write(
            root.resolve("modify.txt"),
            "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
            root.resolve("delete.txt"),
            "delete me\n".getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
            root.resolve("old/name.txt"),
            "old content\n".getBytes(StandardCharsets.UTF_8)
        );
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .requireReadBeforeMutation(false)
            .build();
        String patch = "*** Begin Patch\n"
            + "*** Add File: nested/new.txt\n"
            + "+created\n"
            + "*** Delete File: delete.txt\n"
            + "*** Update File: modify.txt\n"
            + "@@\n-two\n+TWO\n"
            + "@@\n-four\n+FOUR\n"
            + "*** Update File: old/name.txt\n"
            + "*** Move to: renamed/dir/name.txt\n"
            + "@@\n-old content\n+new content\n"
            + "*** End Patch";
        ScriptedModel model = new ScriptedModel(
            tool("patch-1", "apply_patch",
                "{\"patch\":\"" + json(patch) + "\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("apply changes");

        ToolResult applied = result.getState().getToolResults().get(0).getResult();
        assertFalse(applied.isError());
        assertEquals(4, applied.getMetadata().get("actions"));
        assertEquals(5, applied.getMetadata().get("pathsAffected"));
        assertTrue(applied.getContent().contains("A nested/new.txt"));
        assertTrue(applied.getContent().contains("D delete.txt"));
        assertTrue(applied.getContent().contains("M modify.txt"));
        assertTrue(applied.getContent().contains(
            "M old/name.txt -> renamed/dir/name.txt"
        ));
        assertEquals("created\n", read(root.resolve("nested/new.txt")));
        assertEquals("one\nTWO\nthree\nFOUR\n",
            read(root.resolve("modify.txt")));
        assertFalse(Files.exists(root.resolve("delete.txt")));
        assertFalse(Files.exists(root.resolve("old/name.txt")));
        assertEquals("new content\n",
            read(root.resolve("renamed/dir/name.txt")));
    }

    @Test
    void applyPatchRequiresReadBeforeChangingExistingFile() throws Exception {
        Path root = workspace();
        Path file = root.resolve("source.txt");
        Files.write(file, "before\n".getBytes(StandardCharsets.UTF_8));
        WorkspaceTools tools = WorkspaceTools.builder(root).build();
        String patch = "*** Begin Patch\n"
            + "*** Update File: source.txt\n"
            + "@@\n-before\n+after\n"
            + "*** End Patch";
        ScriptedModel model = new ScriptedModel(
            tool("patch-1", "apply_patch",
                "{\"patch\":\"" + json(patch) + "\"}"),
            tool("read-1", "read_file", "{\"file_path\":\"source.txt\"}"),
            tool("patch-2", "apply_patch",
                "{\"patch\":\"" + json(patch) + "\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("update source");

        List<ToolExecutionRecord> records = result.getState().getToolResults();
        assertEquals("FILE_NOT_OBSERVED",
            records.get(0).getResult().getErrorInfo().getCode());
        assertFalse(records.get(1).getResult().isError());
        assertFalse(records.get(2).getResult().isError());
        assertEquals("after\n", read(file));
    }

    @Test
    void applyPatchPreflightLeavesEveryFileUnchangedOnContextFailure()
            throws Exception {
        Path root = workspace();
        Path existing = root.resolve("existing.txt");
        Files.write(existing, "original\n".getBytes(StandardCharsets.UTF_8));
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .requireReadBeforeMutation(false)
            .build();
        String patch = "*** Begin Patch\n"
            + "*** Add File: created.txt\n"
            + "+would be created\n"
            + "*** Update File: existing.txt\n"
            + "@@\n-missing\n+changed\n"
            + "*** End Patch";
        ScriptedModel model = new ScriptedModel(
            tool("patch-1", "apply_patch",
                "{\"patch\":\"" + json(patch) + "\"}"),
            finalAnswer("handled")
        );

        AgentResult result = agent(tools, model).run("apply invalid patch");

        ToolResult failed = result.getState().getToolResults().get(0).getResult();
        assertEquals("PATCH_CONTEXT_NOT_FOUND", failed.getErrorInfo().getCode());
        assertFalse(Files.exists(root.resolve("created.txt")));
        assertEquals("original\n", read(existing));
    }

    @Test
    void applyPatchPreservesCrLfAndMixedContextEndings() throws Exception {
        Path root = workspace();
        Path file = root.resolve("lines.txt");
        Path mixed = root.resolve("mixed.txt");
        Files.write(
            file,
            "one\r\ntwo\r\nthree\r\n".getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
            mixed,
            "one\r\ntwo\rthree\nfour\r\n".getBytes(StandardCharsets.UTF_8)
        );
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .requireReadBeforeMutation(false)
            .build();
        String patch = "*** Begin Patch\n"
            + "*** Update File: lines.txt\n"
            + "@@\n"
            + "-one\n+ONE\n two\n+between\n three\n"
            + "*** Update File: mixed.txt\n"
            + "@@\n one\n two\n-three\n+THREE\n four\n"
            + "*** End Patch";
        ScriptedModel model = new ScriptedModel(
            tool("patch-1", "apply_patch",
                "{\"patch\":\"" + json(patch) + "\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("patch CRLF file");

        assertFalse(result.getState().getToolResults().get(0)
            .getResult().isError());
        assertEquals("ONE\r\ntwo\r\nbetween\r\nthree\r\n", read(file));
        assertEquals("one\r\ntwo\rTHREE\r\nfour\r\n", read(mixed));
    }

    @Test
    void applyPatchSupportsContextHintsEofAndPureAppend() throws Exception {
        Path root = workspace();
        Path target = root.resolve("target.txt");
        Path append = root.resolve("append.txt");
        Files.write(
            target,
            "section\nalpha  \ntail\n".getBytes(StandardCharsets.UTF_8)
        );
        Files.write(append, "base\n".getBytes(StandardCharsets.UTF_8));
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .requireReadBeforeMutation(false)
            .build();
        String patch = " *** Begin Patch\n"
            + "  *** Update File: target.txt\n"
            + "@@ section\n-alpha\n+ALPHA\n"
            + "@@\n-tail\n+TAIL\n*** End of File\n"
            + "*** Update File: append.txt\n"
            + "@@\n+appended\n"
            + " *** End Patch ";
        ScriptedModel model = new ScriptedModel(
            tool("patch-1", "apply_patch",
                "{\"patch\":\"" + json(patch) + "\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("apply contextual patch");

        assertFalse(result.getState().getToolResults().get(0)
            .getResult().isError());
        assertEquals("section\nALPHA\nTAIL\n", read(target));
        assertEquals("base\nappended\n", read(append));
    }

    @Test
    void applyPatchRejectsWorkspaceEscapeAndOversizedInput() throws Exception {
        Path root = workspace();
        Path outside = temporary.resolve("outside-patch.txt");
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .maxPatchBytes(512)
            .build();
        String outsidePatch = "*** Begin Patch\n"
            + "*** Add File: " + outside + "\n"
            + "+secret\n*** End Patch";
        String largePatch = "*** Begin Patch\n*** Add File: large.txt\n+"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
            + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz\n"
            + "*** End Patch";
        ScriptedModel model = new ScriptedModel(
            tool("patch-1", "apply_patch",
                "{\"patch\":\"" + json(outsidePatch) + "\"}"),
            tool("patch-2", "apply_patch",
                "{\"patch\":\"" + json(largePatch) + "\"}"),
            finalAnswer("handled")
        );

        AgentResult result = agent(tools, model).run("reject unsafe patches");

        List<ToolExecutionRecord> records = result.getState().getToolResults();
        assertEquals("PATH_OUTSIDE_WORKSPACE",
            records.get(0).getResult().getErrorInfo().getCode());
        assertEquals("PATCH_TOO_LARGE",
            records.get(1).getResult().getErrorInfo().getCode());
        assertFalse(Files.exists(outside));
        assertFalse(Files.exists(root.resolve("large.txt")));
    }

    @Test
    void applyPatchRollsBackEarlierWritesWhenCommitFails() throws Exception {
        Path root = workspace();
        Path source = root.resolve("source.txt");
        Path blocker = root.resolve("blocker");
        Files.write(source, "old\n".getBytes(StandardCharsets.UTF_8));
        Files.write(blocker, "not a directory\n".getBytes(StandardCharsets.UTF_8));
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .requireReadBeforeMutation(false)
            .build();
        String patch = "*** Begin Patch\n"
            + "*** Add File: created.txt\n+temporary\n"
            + "*** Update File: source.txt\n"
            + "*** Move to: blocker/child.txt\n"
            + "@@\n-old\n+new\n"
            + "*** End Patch";
        ScriptedModel model = new ScriptedModel(
            tool("patch-1", "apply_patch",
                "{\"patch\":\"" + json(patch) + "\"}"),
            finalAnswer("handled")
        );

        AgentResult result = agent(tools, model).run("exercise rollback");

        ToolResult failed = result.getState().getToolResults().get(0).getResult();
        assertEquals("PATCH_APPLY_FAILED", failed.getErrorInfo().getCode());
        assertFalse(Files.exists(root.resolve("created.txt")));
        assertEquals("old\n", read(source));
        assertEquals("not a directory\n", read(blocker));
    }

    private Path workspace() throws IOException {
        Path root = temporary.resolve("workspace-" + System.nanoTime());
        return Files.createDirectories(root);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Agent agent(WorkspaceTools tools, ChatModel model) {
        return Agent.builder()
            .name("coding-agent")
            .description("coding-agent")
            .model(model)
            .instructions(tools.getInstructions())
            .tools(tools.getTools())
            .maxSteps(10)
            .build();
    }

    private static ModelResponse tool(String id, String name, String arguments) {
        return ModelResponse.of(ChatMessage.assistant(
            null,
            Collections.singletonList(new ToolCall(id, name, arguments))
        ));
    }

    private static ModelResponse finalAnswer(String content) {
        return ModelResponse.of(ChatMessage.assistant(content));
    }

    private static CompletableFuture<ModelResponse> completed(ModelResponse response) {
        return CompletableFuture.completedFuture(response);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private static final class ScriptedModel implements ChatModel {
        private final List<ModelResponse> responses;
        private final List<ModelRequest> requests = new ArrayList<ModelRequest>();
        private int index;

        private ScriptedModel(ModelResponse... responses) {
            this.responses = java.util.Arrays.asList(responses);
        }

        @Override
        public synchronized CompletableFuture<ModelResponse> generate(
                ModelRequest request) {
            requests.add(request);
            if (index >= responses.size()) {
                CompletableFuture<ModelResponse> failure =
                    new CompletableFuture<ModelResponse>();
                failure.completeExceptionally(
                    new IllegalStateException("No scripted response")
                );
                return failure;
            }
            return CompletableFuture.completedFuture(responses.get(index++));
        }
    }
}
