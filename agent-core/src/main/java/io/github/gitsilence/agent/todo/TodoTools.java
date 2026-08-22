package io.github.gitsilence.agent.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.tool.AnnotatedTools;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.util.List;

public final class TodoTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Tool> TOOLS = AnnotatedTools.from(new Handlers());

    private TodoTools() {
    }

    public static List<Tool> all() {
        return TOOLS;
    }

    public static Tool create() {
        return find("todo_create");
    }

    public static Tool update() {
        return find("todo_update");
    }

    public static Tool complete() {
        return find("todo_complete");
    }

    public static Tool list() {
        return find("todo_list");
    }

    private static Tool find(String name) {
        for (Tool tool : TOOLS) {
            if (tool.definition().getName().equals(name)) {
                return tool;
            }
        }
        throw new IllegalStateException("Missing built-in todo tool: " + name);
    }

    private static String toJson(Todo todo) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", todo.getId());
        node.put("title", todo.getTitle());
        if (todo.getDetails() != null) {
            node.put("details", todo.getDetails());
        }
        node.put("status", todo.getStatus().name());
        node.put("createdAt", todo.getCreatedAt().toString());
        node.put("updatedAt", todo.getUpdatedAt().toString());
        return node.toString();
    }

    private static String toJson(List<Todo> todos) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Todo todo : todos) {
            try {
                array.add(MAPPER.readTree(toJson(todo)));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot serialize todo", e);
            }
        }
        return array.toString();
    }

    private static final class Handlers {

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "todo_create",
            description = "Creates a todo in the current agent run"
        )
        public String create(
                @ToolParam(description = "Short todo title") String title,
                @ToolParam(
                    description = "Optional todo details", required = false
                ) String details,
                ToolContext context) {
            return toJson(context.todos().create(title, details));
        }

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "todo_update",
            description = "Updates a todo in the current agent run"
        )
        public String update(
                @ToolParam(description = "Todo identifier") String id,
                @ToolParam(description = "New title", required = false) String title,
                @ToolParam(description = "New details", required = false) String details,
                @ToolParam(description = "New todo status", required = false)
                    TodoStatus status,
                ToolContext context) {
            return toJson(context.todos().update(id, title, details, status));
        }

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "todo_complete",
            description = "Marks a todo as completed"
        )
        public String complete(
                @ToolParam(description = "Todo identifier") String id,
                ToolContext context) {
            return toJson(context.todos().complete(id));
        }

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "todo_list",
            description = "Lists todos in the current agent run"
        )
        public String list(ToolContext context) {
            return toJson(context.todos().list());
        }
    }
}
