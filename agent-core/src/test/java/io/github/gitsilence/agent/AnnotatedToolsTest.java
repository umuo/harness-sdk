package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.tool.AnnotatedTools;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotatedToolsTest {

    @Test
    void createsDefinitionsFromAnnotatedMethods() {
        List<Tool> tools = AnnotatedTools.from(new MathTools());

        assertEquals(1, tools.size());
        assertEquals("add", tools.get(0).definition().getName());
        assertTrue(tools.get(0).definition().getInputSchema().contains("\"a\""));
        assertTrue(tools.get(0).definition().getInputSchema().contains("\"integer\""));
    }

    @Test
    void executesAnnotatedMethodThroughAgentLoop() {
        AtomicInteger turn = new AtomicInteger();
        ChatModel model = request -> {
            if (turn.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall("add-call", "add", "{\"a\":2,\"b\":3}")
                    ))
                ));
            }
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("five"))
            );
        };
        Agent agent = Agent.builder()
            .name("math")
            .description("Test math agent")
            .instructions("Use tools")
            .model(model)
            .toolsFrom(new MathTools())
            .build();

        AgentResult result = agent.run("add two and three");

        assertEquals("five", result.getOutput());
        assertEquals("5", result.getState().getToolResults().get(0)
            .getResult().getContent());
    }

    public static final class MathTools {

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "add",
            description = "Adds two integers"
        )
        public int add(
                @ToolParam(name = "a") int a,
                @ToolParam(name = "b") int b) {
            return a + b;
        }
    }
}
