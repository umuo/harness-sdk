package io.github.gitsilence.agent.tools.builtin;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;
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
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".git"));
        Files.write(root.resolve("z.java"), new byte[0]);
        Files.write(root.resolve("a.java"), new byte[0]);
        Files.write(root.resolve("src/b.java"), new byte[0]);
        Files.write(root.resolve(".git/hidden.java"), new byte[0]);
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .globMaxResults(2)
            .build();
        ScriptedModel model = new ScriptedModel(
            tool("glob-1", "glob", "{\"pattern\":\"*.java\"}"),
            finalAnswer("done")
        );

        AgentResult result = agent(tools, model).run("find java");

        String output = result.getState().getToolResults().get(0)
            .getResult().getContent();
        assertTrue(output.startsWith("a.java\nsrc/b.java"));
        assertTrue(output.contains("showing 2 of 3 files"));
        assertFalse(output.contains("hidden.java"));
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
        WorkspaceTools tools = WorkspaceTools.builder(root)
            .enableBash(true)
            .bashMaxStreamBytes(256)
            .bashSpillDirectory(root.resolve(".agent-output"))
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

    private Path workspace() throws IOException {
        Path root = temporary.resolve("workspace-" + System.nanoTime());
        return Files.createDirectories(root);
    }

    private static Agent agent(WorkspaceTools tools, ChatModel model) {
        return Agent.builder()
            .name("coding-agent")
            .description("coding-agent")
            .model(model)
            .skill(tools.asSkill())
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
