package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.skill.BuiltInSkills;
import io.github.gitsilence.agent.tool.annotation.Tool;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

public final class OpenAiAgentExample {

    private OpenAiAgentExample() {
    }

    public static void main(String[] args) {
        String apiKey = requireEnvironment("OPENAI_API_KEY");
        String modelName = requireEnvironment("LLM_MODEL");

        OpenAiChatModel model = OpenAiChatModel.builder()
            .apiKey(apiKey)
            .model(modelName)
            .build();

        Agent mathAgent = Agent.builder()
            .name("math_agent")
            .description("Solves arithmetic tasks with a calculator")
            .instructions("Use the calculator and return a concise result.")
            .model(model)
            .toolsFrom(new ArithmeticTools())
            .maxSteps(5)
            .build();

        Agent supervisor = Agent.builder()
            .name("supervisor")
            .description("Delegates specialist tasks and answers the user")
            .instructions(
                "Delegate arithmetic to math_agent. "
                    + "Use todos only when the request has multiple steps."
            )
            .model(model)
            .tool(mathAgent)
            .skill(BuiltInSkills.todos())
            .maxSteps(10)
            .build();

        String input = args.length == 0
            ? "What is 17 multiplied by 23?"
            : String.join(" ", args);
        AgentResult result = supervisor.run(input);
        System.out.println(result.getOutput());
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    public static final class ArithmeticTools {

        @Tool(name = "multiply", description = "Multiplies two integers")
        public long multiply(
                @ToolParam(name = "a", description = "First integer") long a,
                @ToolParam(name = "b", description = "Second integer") long b) {
            return a * b;
        }

        @Tool(name = "add", description = "Adds two integers")
        public long add(
                @ToolParam(name = "a", description = "First integer") long a,
                @ToolParam(name = "b", description = "Second integer") long b) {
            return a + b;
        }
    }
}
