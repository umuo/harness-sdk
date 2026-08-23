package io.github.gitsilence.agent.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.tool.AbstractTool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * A single stateful planning tool whose data is scoped to the current Agent Turn.
 */
public final class TodoTool extends AbstractTool<TodoTool.Input> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TodoTool() {
        super(
            "todo",
            "Updates the task plan. Provide an optional explanation and a full list of plan items, each with a step and status. At most one step should be IN_PROGRESS at a time. This replaces the entire previous plan.",
            Input.class
        );
    }

    public static TodoTool create() {
        return new TodoTool();
    }

    @Override
    protected ToolResult execute(Input input, ToolContext context) {
        TodoStore store = context.todos();
        
        List<Todo> newTodos = new ArrayList<Todo>();
        if (input.plan != null) {
            for (PlanItemArg item : input.plan) {
                if (item.step != null && !item.step.trim().isEmpty() && item.status != null) {
                    newTodos.add(new Todo(item.step, item.status));
                }
            }
        }
        
        store.setTodos(newTodos);
        return result(input.explanation, store.list());
    }

    private static ToolResult result(String explanation, List<Todo> todos) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("ok", true);
        if (explanation != null && !explanation.isEmpty()) {
            root.put("explanation", explanation);
        }
        ArrayNode entries = root.putArray("plan");
        for (Todo todo : todos) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("step", todo.getStep());
            node.put("status", todo.getStatus().name());
            entries.add(node);
        }
        return ToolResult.success(root.toString())
            .withMetadata("todoCount", todos.size());
    }

    /** Mutable input POJO used for schema generation and JSON binding. */
    public static final class Input {
        @ToolParam(description = "Optional explanation for this plan update.", required = false)
        private String explanation;

        @ToolParam(description = "The list of steps replacing the current plan.", required = false)
        private List<PlanItemArg> plan;
    }

    public static final class PlanItemArg {
        @ToolParam(description = "Task step text.")
        private String step;

        @ToolParam(description = "Step status: PENDING, IN_PROGRESS, or COMPLETED.")
        private TodoStatus status;

        public String getStep() { return step; }
        public void setStep(String step) { this.step = step; }
        public TodoStatus getStatus() { return status; }
        public void setStatus(TodoStatus status) { this.status = status; }
    }
}
