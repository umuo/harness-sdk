package io.github.gitsilence.agent.todo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TodoStore {

    private final Map<String, Todo> todos = new LinkedHashMap<String, Todo>();

    public synchronized Todo create(String title, String details) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Todo title must not be blank");
        }
        Instant now = Instant.now();
        Todo todo = new Todo(
            UUID.randomUUID().toString(),
            title,
            details,
            TodoStatus.PENDING,
            now,
            now
        );
        todos.put(todo.getId(), todo);
        return todo;
    }

    public synchronized Todo update(String id,
                                    String title,
                                    String details,
                                    TodoStatus status) {
        Todo current = require(id);
        Todo updated = current.update(title, details, status);
        todos.put(id, updated);
        return updated;
    }

    public synchronized Todo complete(String id) {
        return update(id, null, null, TodoStatus.COMPLETED);
    }

    public synchronized Optional<Todo> find(String id) {
        return Optional.ofNullable(todos.get(id));
    }

    public synchronized List<Todo> list() {
        return Collections.unmodifiableList(new ArrayList<Todo>(todos.values()));
    }

    private Todo require(String id) {
        Todo todo = todos.get(id);
        if (todo == null) {
            throw new IllegalArgumentException("Unknown todo id: " + id);
        }
        return todo;
    }
}
