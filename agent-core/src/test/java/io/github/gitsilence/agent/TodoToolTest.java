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
                            "{\"action\":\"ADD\",\"title\":\"Write tests\"}"
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
            .instructions("Use todo for multi-step work.")
            .model(model)
            .tool(TodoTool.create())
            .build();

        AgentResult result = agent.run("plan tests");

        assertEquals(1, result.getState().getTodos().size());
        assertEquals("Write tests", result.getState().getTodos().get(0).getTitle());
        assertEquals(TodoStatus.PENDING, result.getState().getTodos().get(0).getStatus());
        assertEquals(1, agent.getToolRegistry().definitions().size());
        assertEquals(
            "todo",
            agent.getToolRegistry().definitions().iterator().next().getName()
        );
    }

    @Test
    void supportsUpdateCompleteListAndClearActions() {
        TodoTool tool = TodoTool.create();
        ToolContext context = context();

        tool.execute(ToolArguments.parse(
            "{\"action\":\"ADD\",\"title\":\"Implement\"}"
        ), context).join();
        String id = context.todos().list().get(0).getId();

        tool.execute(ToolArguments.parse(
            "{\"action\":\"UPDATE\",\"id\":\"" + id
                + "\",\"status\":\"IN_PROGRESS\"}"
        ), context).join();
        assertEquals(TodoStatus.IN_PROGRESS, context.todos().list().get(0).getStatus());

        tool.execute(ToolArguments.parse(
            "{\"action\":\"COMPLETE\",\"id\":\"" + id + "\"}"
        ), context).join();
        assertEquals(TodoStatus.COMPLETED, context.todos().list().get(0).getStatus());

        ToolResult listed = tool.execute(
            ToolArguments.parse("{\"action\":\"LIST\"}"), context
        ).join();
        assertTrue(listed.getContent().contains("\"status\":\"COMPLETED\""));

        ToolResult cleared = tool.execute(
            ToolArguments.parse("{\"action\":\"CLEAR\"}"), context
        ).join();
        assertTrue(cleared.getContent().contains("\"cleared\":1"));
        assertTrue(context.todos().list().isEmpty());
    }

    @Test
    void schemaDescribesOneActionBasedTool() {
        TodoTool tool = TodoTool.create();

        assertEquals("todo", tool.definition().getName());
        assertTrue(tool.definition().getInputSchema().contains(
            "\"enum\":[\"LIST\",\"ADD\",\"UPDATE\",\"COMPLETE\",\"CLEAR\"]"
        ));
        assertTrue(tool.definition().getInputSchema().contains("\"required\":[\"action\"]"));
    }

    @Test
    void reportsUnknownIdsWithActionableStructuredError() {
        AtomicInteger call = new AtomicInteger();
        ChatModel model = request -> {
            if (call.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall(
                            "todo-call",
                            "todo",
                            "{\"action\":\"COMPLETE\",\"id\":\"missing\"}"
                        )
                    ))
                ));
            }
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("recovered"))
            );
        };
        Agent agent = Agent.builder()
            .name("planner")
            .description("Plans work")
            .model(model)
            .tool(TodoTool.create())
            .build();

        AgentResult result = agent.run("finish the task");

        ToolResult failure = result.getState().getToolResults().get(0).getResult();
        assertTrue(failure.isError());
        assertEquals("TODO_NOT_FOUND", failure.getErrorInfo().getCode());
        assertTrue(failure.getContent().contains("Call todo with action LIST"));
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
