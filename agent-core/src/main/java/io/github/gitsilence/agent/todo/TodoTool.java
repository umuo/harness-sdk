package io.github.gitsilence.agent.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.tool.AbstractTool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolErrorInfo;
import io.github.gitsilence.agent.tool.ToolFailureException;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.util.List;

/**
 * A single stateful planning tool whose data is scoped to the current Agent Turn.
 */
public final class TodoTool extends AbstractTool<TodoTool.Input> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TodoTool() {
        super(
            "todo",
            "Manages the current task plan. Use ADD for multi-step work, UPDATE "
                + "when work starts or details change, COMPLETE immediately when an "
                + "item finishes, LIST to inspect the plan, and CLEAR only when the "
                + "plan is no longer needed.",
            Input.class
        );
    }

    public static TodoTool create() {
        return new TodoTool();
    }

    @Override
    protected ToolResult execute(Input input, ToolContext context) {
        TodoStore store = context.todos();
        switch (input.action) {
            case LIST:
                rejectFields(input, false, false, false, false);
                return result(input.action, null, store.list(), null);
            case ADD:
                rejectFields(input, false, true, true, false);
                requireText(input.title, "title", input.action);
                Todo added = store.create(input.title, input.details);
                return result(input.action, added, store.list(), null);
            case UPDATE:
                requireText(input.id, "id", input.action);
                if (input.title != null) {
                    requireText(input.title, "title", input.action);
                }
                if (input.title == null && input.details == null && input.status == null) {
                    throw invalid(
                        input.action,
                        "UPDATE requires at least one of title, details, or status.",
                        "Supply the fields that should change, or use LIST to inspect todos."
                    );
                }
                return update(store, input);
            case COMPLETE:
                rejectFields(input, true, false, false, false);
                requireText(input.id, "id", input.action);
                return complete(store, input);
            case CLEAR:
                rejectFields(input, false, false, false, false);
                int cleared = store.clear();
                return result(input.action, null, store.list(), cleared);
            default:
                throw invalid(
                    input.action,
                    "Unsupported todo action: " + input.action,
                    "Use one of LIST, ADD, UPDATE, COMPLETE, or CLEAR."
                );
        }
    }

    private static ToolResult update(TodoStore store, Input input) {
        try {
            Todo updated = store.update(
                input.id, input.title, input.details, input.status
            );
            return result(input.action, updated, store.list(), null);
        } catch (IllegalArgumentException error) {
            throw notFound(input.id, error);
        }
    }

    private static ToolResult complete(TodoStore store, Input input) {
        try {
            Todo completed = store.complete(input.id);
            return result(input.action, completed, store.list(), null);
        } catch (IllegalArgumentException error) {
            throw notFound(input.id, error);
        }
    }

    private static ToolResult result(TodoAction action,
                                     Todo affected,
                                     List<Todo> todos,
                                     Integer cleared) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("ok", true);
        root.put("action", action.name());
        if (affected != null) {
            root.set("todo", toJson(affected));
        }
        if (cleared != null) {
            root.put("cleared", cleared.intValue());
        }
        ArrayNode entries = root.putArray("todos");
        for (Todo todo : todos) {
            entries.add(toJson(todo));
        }
        return ToolResult.success(root.toString())
            .withMetadata("todoAction", action.name())
            .withMetadata("todoCount", todos.size());
    }

    private static ObjectNode toJson(Todo todo) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", todo.getId());
        node.put("title", todo.getTitle());
        if (todo.getDetails() != null) {
            node.put("details", todo.getDetails());
        }
        node.put("status", todo.getStatus().name());
        node.put("createdAt", todo.getCreatedAt().toString());
        node.put("updatedAt", todo.getUpdatedAt().toString());
        return node;
    }

    private static void requireText(String value,
                                    String field,
                                    TodoAction action) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(
                action,
                action + " requires a non-blank " + field + ".",
                "Provide '" + field + "' and retry the same action."
            );
        }
    }

    private static void rejectFields(Input input,
                                     boolean idAllowed,
                                     boolean titleAllowed,
                                     boolean detailsAllowed,
                                     boolean statusAllowed) {
        String unexpected = null;
        if (!idAllowed && input.id != null) unexpected = "id";
        else if (!titleAllowed && input.title != null) unexpected = "title";
        else if (!detailsAllowed && input.details != null) unexpected = "details";
        else if (!statusAllowed && input.status != null) unexpected = "status";
        if (unexpected != null) {
            throw invalid(
                input.action,
                "Argument '" + unexpected + "' is not used by " + input.action + ".",
                "Remove unrelated arguments and retry the action."
            );
        }
    }

    private static ToolFailureException invalid(TodoAction action,
                                                String message,
                                                String recovery) {
        return new ToolFailureException(
            ToolErrorInfo.builder("TODO_INVALID_ARGUMENTS", message)
                .retryable(true)
                .recoveryHint(recovery)
                .detail("action", action == null ? "missing" : action.name())
                .build()
        );
    }

    private static ToolFailureException notFound(String id, Throwable cause) {
        return new ToolFailureException(
            ToolErrorInfo.builder("TODO_NOT_FOUND", "Unknown todo id: " + id)
                .retryable(true)
                .recoveryHint("Call todo with action LIST, then retry with a valid id.")
                .detail("id", id)
                .build(),
            cause
        );
    }

    /** Mutable input POJO used for schema generation and JSON binding. */
    public static final class Input {
        @ToolParam(description = "Operation: LIST, ADD, UPDATE, COMPLETE, or CLEAR")
        private TodoAction action;

        @ToolParam(description = "Todo identifier for UPDATE or COMPLETE", required = false)
        private String id;

        @ToolParam(description = "Todo title for ADD or UPDATE", required = false)
        private String title;

        @ToolParam(description = "Todo details for ADD or UPDATE", required = false)
        private String details;

        @ToolParam(
            description = "New status for UPDATE: PENDING, IN_PROGRESS, or COMPLETED",
            required = false
        )
        private TodoStatus status;
    }
}
