package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.skill.BuiltInSkills;
import io.github.gitsilence.agent.todo.TodoStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TodoSkillTest {

    @Test
    void storesTodosInCurrentAgentState() {
        AtomicInteger call = new AtomicInteger();
        ChatModel model = request -> {
            if (call.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall(
                            "todo-call",
                            "todo_create",
                            "{\"title\":\"Write tests\"}"
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
            .instructions("Plan the task")
            .model(model)
            .skill(BuiltInSkills.todos())
            .build();

        AgentResult result = agent.run("plan tests");

        assertEquals(1, result.getState().getTodos().size());
        assertEquals("Write tests", result.getState().getTodos().get(0).getTitle());
        assertEquals(TodoStatus.PENDING, result.getState().getTodos().get(0).getStatus());
    }
}
