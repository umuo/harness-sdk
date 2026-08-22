package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.tool.BoundedToolResultPolicy;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
import io.github.gitsilence.agent.tool.ToolOutputReference;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.Tools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultPolicyTest {

    @TempDir
    Path temporary;

    @Test
    void boundsUtf8OutputAndPreservesCompleteContent() throws Exception {
        String content = repeat("开头", 100) + "\n" + repeat("结尾", 100);
        Path outputDirectory = temporary.resolve("bounded-output");
        ToolResult bounded = new BoundedToolResultPolicy(
            256, 5, outputDirectory
        )
            .apply(ToolResult.success(content));

        assertTrue(bounded.getContent().contains("tool output truncated"));
        assertTrue(bounded.getContent().startsWith("开头"));
        assertTrue(bounded.getContent().endsWith("结尾"));
        assertTrue(bounded.getContent().getBytes(StandardCharsets.UTF_8).length <= 256);
        assertEquals(true, bounded.getMetadata().get("toolOutputTruncated"));
        assertEquals("head_tail", bounded.getMetadata().get("toolOutputStrategy"));
        assertEquals(1, bounded.getOutputReferences().size());
        ToolOutputReference reference = bounded.getOutputReferences().get(0);
        assertEquals(ToolOutputReference.Kind.TEMPORARY_FILE, reference.getKind());
        assertEquals(content, new String(
            Files.readAllBytes(java.nio.file.Paths.get(reference.getPath())),
            StandardCharsets.UTF_8
        ));
        assertEquals(reference.getPath(),
            bounded.getMetadata().get("toolOutputFullPath"));
    }

    @Test
    void onlyBoundedToolContentIsAddedToModelHistory() {
        AtomicInteger round = new AtomicInteger();
        AtomicReference<String> returnedToModel = new AtomicReference<String>();
        ChatModel model = request -> {
            if (round.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall("large-1", "large", "{}")
                    ))
                ));
            }
            returnedToModel.set(request.getMessages()
                .get(request.getMessages().size() - 1).getContent());
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("done"))
            );
        };
        Tool large = Tools.sync(
            ToolDefinition.builder().name("large").description("Large output").build(),
            (arguments, context) -> ToolResult.success(
                "HEAD-" + repeat("x", 2000) + "-TAIL"
            )
        );
        Agent agent = Agent.builder()
            .name("bounded")
            .description("bounded")
            .model(model)
            .tool(large)
            .toolResultPolicy(new BoundedToolResultPolicy(
                512, 20, temporary.resolve("agent-output")
            ))
            .build();

        AgentResult result = agent.run("run tool");

        assertEquals("done", result.getOutput());
        assertTrue(returnedToModel.get().startsWith("HEAD-"));
        assertTrue(returnedToModel.get().endsWith("-TAIL"));
        assertTrue(returnedToModel.get().contains("tool output truncated"));
        assertEquals(true, result.getState().getToolResults().get(0)
            .getResult().getMetadata().get("toolOutputTruncated"));
    }

    @Test
    void referencedSourceIsNeverCopiedIntoAnotherTemporaryFile() throws Exception {
        Path source = temporary.resolve("already-recoverable.txt");
        String content = repeat("source-data\n", 200);
        Files.write(source, content.getBytes(StandardCharsets.UTF_8));
        Path outputDirectory = temporary.resolve("should-stay-empty");
        ToolResult sourceResult = ToolResult.success(content)
            .withOutputReference(ToolOutputReference.sourceFile(
                source, "Read the source in pages."
            ));

        ToolResult bounded = new BoundedToolResultPolicy(
            256, 5, outputDirectory
        ).apply(sourceResult);

        assertEquals("existing_reference",
            bounded.getMetadata().get("toolOutputPreservation"));
        assertEquals(1, bounded.getOutputReferences().size());
        assertEquals(source.toAbsolutePath().normalize().toString(),
            bounded.getOutputReferences().get(0).getPath());
        assertTrue(bounded.getContent().contains(source.toString()));
        assertFalse(Files.exists(outputDirectory));
    }

    @Test
    void preservationFailureNeverFallsBackToLossyPreview() throws Exception {
        Path invalidDirectory = temporary.resolve("not-a-directory");
        Files.write(
            invalidDirectory, "file".getBytes(StandardCharsets.UTF_8)
        );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> new BoundedToolResultPolicy(256, 5, invalidDirectory)
                .apply(ToolResult.success(repeat("large", 500)))
        );

        assertTrue(failure.getMessage().contains(
            "Cannot preserve complete Tool output"
        ));
    }

    @Test
    void structuredToolFailureTellsModelHowToRecover() {
        AtomicInteger round = new AtomicInteger();
        AtomicReference<String> returnedToModel = new AtomicReference<String>();
        ChatModel model = request -> {
            if (round.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall("edit-1", "edit", "{}")
                    ))
                ));
            }
            returnedToModel.set(request.getMessages()
                .get(request.getMessages().size() - 1).getContent());
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("recovered"))
            );
        };
        Tool edit = Tools.sync(
            ToolDefinition.builder().name("edit").description("Edit").build(),
            (arguments, context) -> {
                throw new ToolFailureException(
                    ToolErrorInfo.builder(
                        "FILE_NOT_OBSERVED", "The file was not read in this Turn"
                    ).retryable(true)
                        .recoveryHint("Read the file, then retry the edit.")
                        .detail("path", "README.md")
                        .build()
                );
            }
        );
        Agent agent = Agent.builder()
            .name("errors")
            .description("errors")
            .model(model)
            .tool(edit)
            .build();

        AgentResult result = agent.run("edit");

        assertTrue(returnedToModel.get().contains("Error [FILE_NOT_OBSERVED]"));
        assertTrue(returnedToModel.get().contains("Recovery: Read the file"));
        ToolErrorInfo error = result.getState().getToolResults().get(0)
            .getResult().getErrorInfo();
        assertNotNull(error);
        assertEquals("FILE_NOT_OBSERVED", error.getCode());
        assertTrue(error.isRetryable());
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
