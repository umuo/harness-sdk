package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** A Supervisor decomposes one task and runs several Agent Tools in parallel. */
public final class ComplexTaskDelegationExample {

    private ComplexTaskDelegationExample() {
    }

    public static void main(String[] args) {
        OpenAiChatModel model = ExampleSupport.realModel();
        try (AgentObservability observability =
                 ExampleSupport.observability("complex-task-delegation")) {
            Agent requirementsAgent = specialist(
                "requirements_agent",
                "分析复杂任务的目标、约束、范围和验收标准",
                "你是需求分析专家。只处理委托给你的需求部分，输出中文的结构化结论，"
                    + "不要替其他专家做架构或风险分析。",
                model,
                observability
            );
            Agent architectureAgent = specialist(
                "architecture_agent",
                "在 Java 8 约束下设计可落地的技术方案",
                "你是 Java 8 架构专家。给出组件边界、数据流、并发策略和渐进式迁移步骤，"
                    + "所有结论必须可实施。",
                model,
                observability
            );
            Agent riskAgent = specialist(
                "risk_agent",
                "识别交付、可靠性、安全和回滚风险",
                "你是技术风险评审专家。输出风险、影响、触发条件、缓解措施和回滚建议，"
                    + "不要重复架构设计。",
                model,
                observability
            );

            Agent supervisor = Agent.builder()
                .name("complex_task_supervisor")
                .description("拆分复杂任务，并并发委托多个专业 SubAgent")
                .instructions(
                    "收到复杂任务后先在内部拆成需求、架构、风险三个互不重叠的子任务。"
                        + "必须在同一个模型响应中同时调用 requirements_agent、"
                        + "architecture_agent 和 risk_agent，使三个 SubAgent 并发执行。"
                        + "收到全部结果后再综合为一份中文方案，明确两周计划、验收标准和回滚点。"
                )
                .model(model)
                .tool(requirementsAgent)
                .tool(architectureAgent)
                .tool(riskAgent)
                .parallelToolCalls(true)
                .maxSteps(8)
                .plugin(observability)
                .build();

            String task = ExampleSupport.task(
                args,
                "请为一个日订单量一百万、仍运行在 Java 8 单体架构上的电商订单系统，"
                    + "设计两周内可交付的稳定性改造方案。不能整体重写或立即拆成微服务，"
                    + "目标是降低重复下单、库存超卖和第三方支付超时造成的故障。"
            );
            AgentResult result = supervisor.run(task);
            verifyDelegation(result);
            ExampleSupport.printResult(result);
        }
    }

    private static Agent specialist(String name,
                                    String description,
                                    String instructions,
                                    OpenAiChatModel model,
                                    AgentObservability observability) {
        return Agent.builder()
            .name(name)
            .description(description)
            .instructions(instructions)
            .model(model)
            .maxSteps(4)
            .plugin(observability)
            .build();
    }

    private static void verifyDelegation(AgentResult result) {
        Set<String> expected = new LinkedHashSet<String>(Arrays.asList(
            "requirements_agent", "architecture_agent", "risk_agent"
        ));
        Set<String> invoked = new LinkedHashSet<String>();
        List<ToolExecutionRecord> delegated =
            new ArrayList<ToolExecutionRecord>();
        for (ToolExecutionRecord record : result.getState().getToolResults()) {
            invoked.add(record.getCall().getName());
            if (expected.contains(record.getCall().getName())) {
                delegated.add(record);
            }
        }
        if (!invoked.containsAll(expected)) {
            expected.removeAll(invoked);
            throw new IllegalStateException(
                "真实模型没有委托全部 SubAgent，缺少：" + expected
                    + "，实际调用：" + invoked
            );
        }
        Instant latestStart = delegated.get(0).getStartedAt();
        Instant earliestCompletion = delegated.get(0).getCompletedAt();
        for (ToolExecutionRecord record : delegated) {
            if (record.getStartedAt().compareTo(latestStart) > 0) {
                latestStart = record.getStartedAt();
            }
            if (record.getCompletedAt().compareTo(earliestCompletion) < 0) {
                earliestCompletion = record.getCompletedAt();
            }
        }
        if (latestStart.compareTo(earliestCompletion) >= 0) {
            throw new IllegalStateException(
                "三个 SubAgent 已调用，但执行区间没有重叠；请检查模型是否在同一轮发出多个 Tool Call"
            );
        }
    }
}
