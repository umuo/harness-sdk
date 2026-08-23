package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;

/** Sends one real LLM Turn and its content to the observability platform. */
public final class ObservabilityExample {

    private ObservabilityExample() {
    }

    public static void main(String[] args) {
        OpenAiChatModel model = ExampleSupport.realModel();
        try (AgentObservability observability =
                 ExampleSupport.observability("observability")) {
            Agent agent = Agent.builder()
                .name("observable_assistant")
                .description("用于验证真实模型请求和响应可观测性的助手")
                .instructions("请用中文回答，并给出结构清晰的结果。")
                .model(model)
                .plugin(observability)
                .build();

            AgentResult result = agent.run(ExampleSupport.task(
                args,
                "请说明 Agent 可观测性为什么需要同时记录模型原始请求、原始响应和 SDK 归一化数据。"
            ));
            ExampleSupport.printResult(result);
            System.out.println("可观测性模式：" + observability.getMode());
        }
    }
}
