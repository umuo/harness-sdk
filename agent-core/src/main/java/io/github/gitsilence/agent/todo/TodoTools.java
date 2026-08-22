package io.github.gitsilence.agent.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.Tools;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class TodoTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TodoTools() {
    }

    public static List<Tool> all() {
        return Arrays.asList(create(), update(), complete(), list());
    }

    public static Tool create() {
        return Tools.sync(
            ToolDefinition.builder()
                .name("todo_create")
                .description("Creates a todo in the current agent run")
                .inputSchema("{\"type\":\"object\",\"properties\":{"
                    + "\"title\":{\"type\":\"string\"},"
                    + "\"details\":{\"type\":\"string\"}},"
                    + "\"required\":[\"title\"]}")
                .build(),
            (arguments, context) -> {
                Todo todo = context.todos().create(
                    arguments.requireString("title"),
                    arguments.optionalString("details").orElse(null)
                );
                return ToolResult.success(toJson(todo));
            }
        );
    }

    public static Tool update() {
        return Tools.sync(
            ToolDefinition.builder()
                .name("todo_update")
                .description("Updates a todo in the current agent run")
                .inputSchema("{\"type\":\"object\",\"properties\":{"
                    + "\"id\":{\"type\":\"string\"},"
                    + "\"title\":{\"type\":\"string\"},"
                    + "\"details\":{\"type\":\"string\"},"
                    + "\"status\":{\"type\":\"string\","
                    + "\"enum\":[\"PENDING\",\"IN_PROGRESS\",\"COMPLETED\"]}},"
                    + "\"required\":[\"id\"]}")
                .build(),
            (arguments, context) -> {
                Optional<String> status = arguments.optionalString("status");
                Todo todo = context.todos().update(
                    arguments.requireString("id"),
                    arguments.optionalString("title").orElse(null),
                    arguments.optionalString("details").orElse(null),
                    status.isPresent() ? TodoStatus.valueOf(status.get()) : null
                );
                return ToolResult.success(toJson(todo));
            }
        );
    }

    public static Tool complete() {
        return Tools.sync(
            ToolDefinition.builder()
                .name("todo_complete")
                .description("Marks a todo as completed")
                .inputSchema("{\"type\":\"object\",\"properties\":{"
                    + "\"id\":{\"type\":\"string\"}},"
                    + "\"required\":[\"id\"]}")
                .build(),
            (arguments, context) -> ToolResult.success(toJson(
                context.todos().complete(arguments.requireString("id"))
            ))
        );
    }

    public static Tool list() {
        return Tools.sync(
            ToolDefinition.builder()
                .name("todo_list")
                .description("Lists todos in the current agent run")
                .build(),
            (arguments, context) -> ToolResult.success(toJson(context.todos().list()))
        );
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
}
