package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.stream.ModelStreamEvent;
import io.github.gitsilence.agent.model.stream.ModelStreamEventType;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.runtime.AgentEventType;
import io.github.gitsilence.agent.runtime.Futures;

import java.util.concurrent.atomic.AtomicInteger;

/** Verifies that a real Provider emits incremental text through the Agent Loop. */
public final class StreamingAgentExample {

    private StreamingAgentExample() {
    }

    public static void main(String[] args) {
        OpenAiChatModel model = ExampleSupport.realModel();
        try (AgentObservability observability =
                 ExampleSupport.observability("streaming-agent")) {
            Agent agent = Agent.builder()
                .name("streaming_writer")
                .description("通过真实流式模型逐段输出中文内容")
                .instructions(
                    "请直接使用中文回答，不调用工具。输出三段简洁内容，并为每段添加小标题。"
                )
                .model(model)
                .maxSteps(3)
                .plugin(observability)
                .build();

            AtomicInteger deltaCount = new AtomicInteger();
            AtomicInteger characterCount = new AtomicInteger();
            System.out.println("===== 流式输出开始 =====");
            AgentResult result = Futures.join(agent.runStreamingAsync(
                ExampleSupport.task(
                    args,
                    "请用三段话解释 Java 8 CompletableFuture 在 Agent 并发工具调用中的作用、"
                        + "异常传播方式和取消语义。"
                ),
                event -> {
                    if (event.getType() != AgentEventType.MODEL_STREAM_EVENT) {
                        return;
                    }
                    ModelStreamEvent stream = event.getModelStreamEvent();
                    if (stream.getType() == ModelStreamEventType.TEXT_DELTA
                            && stream.getDelta() != null) {
                        deltaCount.incrementAndGet();
                        characterCount.addAndGet(stream.getDelta().length());
                        System.out.print(stream.getDelta());
                        System.out.flush();
                    }
                }
            ));
            System.out.println("\n===== 流式输出结束 =====");
            if (deltaCount.get() == 0) {
                throw new IllegalStateException(
                    "真实 Provider 没有产生 TEXT_DELTA，请检查是否支持 SSE 流式输出"
                );
            }
            System.out.println("收到 Delta 数：" + deltaCount.get());
            System.out.println("流式字符数：" + characterCount.get());
            ExampleSupport.printResult(result);
        }
    }
}
