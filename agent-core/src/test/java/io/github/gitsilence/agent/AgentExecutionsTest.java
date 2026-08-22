package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentExecutions;
import io.github.gitsilence.agent.agent.AgentInvocation;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionsTest {

    @Test
    void parallelAgentResultsKeepInvocationOrder() {
        Agent slow = agent("slow", delayedModel("slow-result", 60L));
        Agent fast = agent("fast", delayedModel("fast-result", 5L));

        List<AgentResult> results = AgentExecutions.runParallel(Arrays.asList(
            AgentInvocation.of(slow, "first"),
            AgentInvocation.of(fast, "second")
        )).join();

        assertEquals("slow-result", results.get(0).getOutput());
        assertEquals("fast-result", results.get(1).getOutput());
    }

    @Test
    void parallelAgentFailureCancelsUnfinishedAgents() {
        ChatModel failingModel = request -> {
            CompletableFuture<ModelResponse> failed =
                new CompletableFuture<ModelResponse>();
            failed.completeExceptionally(new IllegalStateException("boom"));
            return failed;
        };
        CompletableFuture<ModelResponse> waitingModel =
            new CompletableFuture<ModelResponse>();
        Agent failing = agent("failing", failingModel);
        Agent waiting = agent("waiting", request -> waitingModel);

        CompletableFuture<List<AgentResult>> execution =
            AgentExecutions.runParallel(Arrays.asList(
                AgentInvocation.of(failing, "fail"),
                AgentInvocation.of(waiting, "wait")
            ));

        assertThrows(CompletionException.class, execution::join);
        assertTrue(waitingModel.isCancelled());
    }

    private static Agent agent(String name, ChatModel model) {
        return Agent.builder()
            .name(name)
            .description(name)
            .model(model)
            .build();
    }

    private static ChatModel delayedModel(String output, long delayMillis) {
        return new ChatModel() {
            @Override
            public CompletableFuture<ModelResponse> generate(ModelRequest request) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return ModelResponse.of(ChatMessage.assistant(output));
                });
            }
        };
    }
}
