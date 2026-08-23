package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.runtime.AgentRunner;
import io.github.gitsilence.agent.runtime.InvocationPath;
import io.github.gitsilence.agent.state.AgentState;
import io.github.gitsilence.agent.todo.TodoStatus;
import io.github.gitsilence.agent.todo.TodoTool;
import io.github.gitsilence.agent.tool.ToolArguments;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoToolTest {

    @Test
    void storesTodosInCurrentAgentStateThroughOneTool() {
        AtomicInteger call = new AtomicInteger();
        ChatModel model = request -> {
            if (call.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall(
                            "todo-call",
                            "todo",
                            "{\"plan\":[{\"step\":\"Write tests\",\"status\":\"PENDING\"}]}"
                        )
                    ))
                ));
            }
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("planned"))
            );
        };
        Agent agent = Agent.builder()
            .name("planner")
            .description("Plans work")
            .instructions("Use todo to manage plan.")
            .model(model)
            .tool(TodoTool.create())
            .build();

        AgentResult result = agent.run("plan tests");

        assertEquals(1, result.getState().getTodos().size());
        assertEquals("Write tests", result.getState().getTodos().get(0).getStep());
        assertEquals(TodoStatus.PENDING, result.getState().getTodos().get(0).getStatus());
        assertEquals(1, agent.getToolRegistry().definitions().size());
        assertEquals(
            "todo",
            agent.getToolRegistry().definitions().iterator().next().getName()
        );
    }

    @Test
    void supportsFullPlanReplacement() {
        TodoTool tool = TodoTool.create();
        ToolContext context = context();

        tool.execute(ToolArguments.parse(
            "{\"plan\":[{\"step\":\"Implement\",\"status\":\"PENDING\"}]}"
        ), context).join();
        assertEquals(1, context.todos().list().size());
        assertEquals("Implement", context.todos().list().get(0).getStep());

        tool.execute(ToolArguments.parse(
            "{\"plan\":[{\"step\":\"Implement\",\"status\":\"IN_PROGRESS\"}]}"
        ), context).join();
        assertEquals(1, context.todos().list().size());
        assertEquals(TodoStatus.IN_PROGRESS, context.todos().list().get(0).getStatus());

        tool.execute(ToolArguments.parse(
            "{\"plan\":[{\"step\":\"Implement\",\"status\":\"COMPLETED\"},{\"step\":\"Test\",\"status\":\"PENDING\"}]}"
        ), context).join();
        assertEquals(2, context.todos().list().size());
        assertEquals(TodoStatus.COMPLETED, context.todos().list().get(0).getStatus());
        assertEquals(TodoStatus.PENDING, context.todos().list().get(1).getStatus());

        ToolResult cleared = tool.execute(
            ToolArguments.parse("{\"plan\":[]}"), context
        ).join();
        assertTrue(context.todos().list().isEmpty());
    }

    @Test
    void schemaDescribesPlanBasedTool() {
        TodoTool tool = TodoTool.create();

        assertEquals("todo", tool.definition().getName());
        assertTrue(tool.definition().getInputSchema().contains("plan"));
        assertTrue(tool.definition().getInputSchema().contains("step"));
        assertTrue(tool.definition().getInputSchema().contains("\"enum\":[\"PENDING\",\"IN_PROGRESS\",\"COMPLETED\"]"));
    }

    private static ToolContext context() {
        AgentState state = new AgentState(
            "turn-1",
            "test-agent",
            Collections.<ChatMessage>emptyList(),
            Collections.<String, Object>emptyMap(),
            Collections.<String, Object>emptyMap()
        );
        return new ToolContext(
            "call-1",
            state,
            AgentRunner.shared(),
            InvocationPath.root("agent-1", "test-agent")
        );
    }
}
