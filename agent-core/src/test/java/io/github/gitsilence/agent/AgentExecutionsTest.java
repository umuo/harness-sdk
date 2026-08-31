package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentExecutions;
import io.github.gitsilence.agent.agent.AgentInvocation;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionsTest {

    @Test
    void parallelAgentResultsKeepInvocationOrder() throws Exception {
        CompletableFuture<ModelResponse> releaseSlow =
            new CompletableFuture<ModelResponse>();
        CompletableFuture<Void> fastStarted = new CompletableFuture<Void>();
        Agent slow = agent("slow", request -> releaseSlow);
        Agent fast = agent("fast", request -> {
            fastStarted.complete(null);
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("fast-result"))
            );
        });

        CompletableFuture<List<AgentResult>> execution =
            AgentExecutions.runParallel(Arrays.asList(
            AgentInvocation.of(slow, "first"),
            AgentInvocation.of(fast, "second")
        ));
        fastStarted.get(2, TimeUnit.SECONDS);
        releaseSlow.complete(
            ModelResponse.of(ChatMessage.assistant("slow-result"))
        );
        List<AgentResult> results = execution.get(2, TimeUnit.SECONDS);

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

}
