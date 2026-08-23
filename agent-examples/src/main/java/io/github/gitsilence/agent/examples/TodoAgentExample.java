package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.todo.Todo;
import io.github.gitsilence.agent.todo.TodoStatus;
import io.github.gitsilence.agent.todo.TodoTool;

/** Exercises the Turn-scoped Todo Tool. */
public final class TodoAgentExample {

    private TodoAgentExample() {
    }

    public static void main(String[] args) {
        OpenAiChatModel model = ExampleSupport.realModel();
        try (AgentObservability observability =
                 ExampleSupport.observability("todo-agent")) {
            Agent agent  = Agent.builder()
                .name("planning_agent")
                .description("使用 Todo 工具规划并跟踪多步骤任务")
                .instructions(
                    "你必须使用 todo 工具管理这个任务。开始时创建一个包含三个具体步骤 (step) 的计划，状态均为 PENDING。"
                        + "随后逐步处理每一项：更新计划将当前步骤设为 IN_PROGRESS，完成后设为 COMPLETED；每次只允许一个步骤为 IN_PROGRESS。"
                        + "直到所有步骤的状态都是 COMPLETED 后，输出中文总结。"
                )
                .model(model)
                .tool(TodoTool.create())
                .maxSteps(16)
                .plugin(observability)
                .build();

            AgentResult result = agent.run(ExampleSupport.task(
                args,
                "请制定 Agent SDK 0.2 版本的发布准备方案，分别覆盖兼容性检查、真实模型回归测试和发布说明。"
            ));
            verifyTodos(result);
            ExampleSupport.printResult(result);
            System.out.println("\n===== 最终 Todo 状态 =====");
            for (Todo todo : result.getState().getTodos()) {
                System.out.println(todo.getStatus() + " | " + todo.getStep());
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
                    "Todo 未完成：" + todo.getStep() + " -> " + todo.getStatus()
                );
            }
        }
    }
}
