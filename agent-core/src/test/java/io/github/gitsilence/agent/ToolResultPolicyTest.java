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
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.Tools;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultPolicyTest {

    @Test
    void boundsUtf8OutputAndRetainsHeadAndTail() {
        String content = repeat("开头", 100) + "\n" + repeat("结尾", 100);
        ToolResult bounded = new BoundedToolResultPolicy(256, 5)
            .apply(ToolResult.success(content));

        assertTrue(bounded.getContent().contains("tool output truncated"));
        assertTrue(bounded.getContent().startsWith("开头"));
        assertTrue(bounded.getContent().endsWith("结尾"));
        assertTrue(bounded.getContent().getBytes(StandardCharsets.UTF_8).length <= 256);
        assertEquals(true, bounded.getMetadata().get("toolOutputTruncated"));
        assertEquals("head_tail", bounded.getMetadata().get("toolOutputStrategy"));
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
            .toolResultPolicy(new BoundedToolResultPolicy(512, 20))
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
