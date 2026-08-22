package io.github.gitsilence.agent.todo;

import java.time.Instant;
import java.util.Objects;

public final class Todo {

    private final String id;
    private final String title;
    private final String details;
    private final TodoStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    Todo(String id,
         String title,
         String details,
         TodoStatus status,
         Instant createdAt,
         Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Objects.requireNonNull(title, "title");
        this.details = details;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    Todo update(String newTitle, String newDetails, TodoStatus newStatus) {
        return new Todo(
            id,
            newTitle == null ? title : newTitle,
            newDetails == null ? details : newDetails,
            newStatus == null ? status : newStatus,
            createdAt,
            Instant.now()
        );
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDetails() {
        return details;
    }

    public TodoStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
