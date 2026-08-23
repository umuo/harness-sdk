package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.todo.Todo;
import io.github.gitsilence.agent.todo.TodoStatus;
import io.github.gitsilence.agent.todo.TodoTool;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exercises ADD, UPDATE, COMPLETE, and LIST on the Turn-scoped Todo Tool. */
public final class TodoAgentExample {

    private TodoAgentExample() {
    }

    public static void main(String[] args) {
        OpenAiChatModel model = ExampleSupport.realModel();
        try (AgentObservability observability =
                 ExampleSupport.observability("todo-agent")) {
            Agent agent = Agent.builder()
                .name("planning_agent")
                .description("使用 Todo 工具规划并跟踪多步骤任务")
                .instructions(
                    "你必须使用 todo 工具管理这个任务。开始时先用 ADD 创建三个具体 Todo；"
                        + "处理每一项前用 UPDATE 将状态改为 IN_PROGRESS，完成后立即用 COMPLETE；"
                        + "最后调用 LIST 检查全部 Todo 都是 COMPLETED，然后才能输出中文总结。"
                        + "不要使用 CLEAR。"
                )
                .model(model)
                .tool(TodoTool.create())
                .maxSteps(16)
                .plugin(observability)
                .build();

            AgentResult result = agent.run(ExampleSupport.task(
                args,
                "请制定 Agent SDK 0.2 版本的发布准备方案，分别覆盖兼容性检查、"
                    + "真实模型回归测试和发布说明，并给出每一部分的完成结论。"
            ));
            verifyTodos(result);
            ExampleSupport.printResult(result);
            System.out.println("\n===== 最终 Todo 状态 =====");
            for (Todo todo : result.getState().getTodos()) {
                System.out.println(todo.getStatus() + " | " + todo.getTitle());
            }
        }
    }

    private static void verifyTodos(AgentResult result) {
        if (result.getState().getTodos().size() < 3) {
            throw new IllegalStateException("真实模型没有创建至少三个 Todo");
        }
        for (Todo todo : result.getState().getTodos()) {
            if (todo.getStatus() != TodoStatus.COMPLETED) {
                throw new IllegalStateException(
                    "Todo 未完成：" + todo.getTitle() + " -> " + todo.getStatus()
                );
            }
        }
        Set<String> actions = new LinkedHashSet<String>();
        for (ToolExecutionRecord record : result.getState().getToolResults()) {
            Object action = record.getResult().getMetadata().get("todoAction");
            if (action != null) actions.add(String.valueOf(action));
        }
        Set<String> expected = new LinkedHashSet<String>(
            Arrays.asList("ADD", "UPDATE", "COMPLETE", "LIST")
        );
        if (!actions.containsAll(expected)) {
            expected.removeAll(actions);
            throw new IllegalStateException(
                "Todo 工具动作覆盖不完整，缺少：" + expected + "，实际动作：" + actions
            );
        }
    }
}
