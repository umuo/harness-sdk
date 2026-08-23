package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.todo.TodoTool;
import io.github.gitsilence.agent.tool.annotation.Tool;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

/**
 * @author gitsilence
 */
public final class OpenAiAgentExample {

    private OpenAiAgentExample() {
    }

    public static void main(String[] args) {
        OpenAiChatModel model = ExampleSupport.realModel();
        try (AgentObservability observability =
                 ExampleSupport.observability("openai-agent")) {
            run(args, model, observability);
        }
    }

    private static void run(String[] args,
                            OpenAiChatModel model,
                            AgentObservability observability) {
        Agent mathAgent = Agent.builder()
            .name("math_agent")
            .description("使用计算器解决算术问题")
            .instructions("必须使用计算工具完成运算，并用中文返回简洁结果。")
            .model(model)
            .toolsFrom(new ArithmeticTools())
            .maxSteps(5)
            .plugin(observability)
            .build();

        Agent supervisor = Agent.builder()
            .name("supervisor")
            .description("把专业任务委托给子 Agent，并整合最终答案")
            .instructions(
                "所有算术任务都委托给 math_agent。"
                    + "只有多步骤任务才使用 todo 工具。最终答案必须使用中文。"
            )
            .model(model)
            .tool(mathAgent)
            .tool(TodoTool.create())
            .plugin(observability)
            .maxSteps(10)
            .build();

        AgentResult result = supervisor.run(ExampleSupport.task(
            args,
            "请计算 17 乘以 23，并说明计算任务由哪个子 Agent 完成。"
        ));
        ExampleSupport.printResult(result);
    }

    public static final class ArithmeticTools {

        @Tool(name = "multiply", description = "计算两个整数的乘积")
        public long multiply(
                @ToolParam(name = "a", description = "第一个整数") long a,
                @ToolParam(name = "b", description = "第二个整数") long b) {
            return a * b;
        }

        @Tool(name = "add", description = "计算两个整数的和")
        public long add(
                @ToolParam(name = "a", description = "第一个整数") long a,
                @ToolParam(name = "b", description = "第二个整数") long b) {
            return a + b;
        }
    }

}
