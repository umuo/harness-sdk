package io.github.gitsilence.agent.todo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TodoStore {

    private List<Todo> todos = new ArrayList<Todo>();

    public synchronized void setTodos(List<Todo> newTodos) {
        if (newTodos == null) {
            this.todos = new ArrayList<Todo>();
        } else {
            this.todos = new ArrayList<Todo>(newTodos);
        }
    }

    public synchronized List<Todo> list() {
        return Collections.unmodifiableList(new ArrayList<Todo>(todos));
    }
}
