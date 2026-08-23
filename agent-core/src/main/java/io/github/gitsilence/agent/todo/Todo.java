package io.github.gitsilence.agent.todo;

import java.util.Objects;

public final class Todo {

    private final String step;
    private final TodoStatus status;

    public Todo(String step, TodoStatus status) {
        this.step = Objects.requireNonNull(step, "step");
        this.status = Objects.requireNonNull(status, "status");
    }

    public String getStep() {
        return step;
    }

    public TodoStatus getStatus() {
        return status;
    }
}
