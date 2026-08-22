package io.github.gitsilence.agent.skill;

import io.github.gitsilence.agent.todo.TodoTools;
import io.github.gitsilence.agent.tool.Tool;

public final class BuiltInSkills {

    private BuiltInSkills() {
    }

    public static Skill todos() {
        Skill.Builder builder = Skill.builder()
            .name("todos")
            .instructions(
                "Use todo tools when a task benefits from explicit planning. "
                    + "Keep todo statuses accurate and complete todos as work finishes."
            );
        for (Tool tool : TodoTools.all()) {
            builder.tool(tool);
        }
        return builder.build();
    }
}
