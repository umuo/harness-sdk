package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/** Verifies progressive Skill instruction and referenced-resource loading. */
public final class SkillsAgentExample {

    private SkillsAgentExample() {
    }

    public static void main(String[] args) {
        String configuredSkills = System.getenv("AGENT_EXAMPLE_SKILLS_DIR");
        Path skillRoot = configuredSkills == null || configuredSkills.trim().isEmpty()
            ? ExampleSupport.repositoryRoot().resolve("agent-examples/skills")
            : Paths.get(configuredSkills).toAbsolutePath().normalize();
        OpenAiChatModel model = ExampleSupport.realModel();

        try (AgentObservability observability =
                 ExampleSupport.observability("agent-skills")) {
            Agent agent = Agent.builder()
                .name("release_note_agent")
                .description("按需加载 Agent Skill 并生成中文发布说明")
                .instructions(
                    "任务匹配 Skill 时必须先调用 skill_load 加载主文档。"
                        + "如果主文档要求读取引用资源，必须再次调用 skill_load，"
                        + "不能凭记忆猜测模板内容。"
                )
                .model(model)
                .skillsFrom(skillRoot)
                .maxSteps(8)
                .plugin(observability)
                .build();

            System.out.println("已注册 Skills："
                + agent.getSkillRegistry().definitions().size());
            AgentResult result = agent.run(ExampleSupport.task(
                args,
                "请使用 chinese-release-note Skill，为以下变更生成发布说明："
                    + "新增按人工任务聚合的可观测列表；模型节点可分别查看 Provider 原始报文和 SDK 归一化数据；"
                    + "兼容旧版 Trace schema。必须遵循 Skill 引用的模板。"
            ));
            verifySkillLoads(result);
            ExampleSupport.printResult(result);
        }
    }

    private static void verifySkillLoads(AgentResult result) {
        Set<String> resources = new LinkedHashSet<String>();
        for (ToolExecutionRecord record : result.getState().getToolResults()) {
            if (!"skill_load".equals(record.getCall().getName())) continue;
            Object resource = record.getResult().getMetadata().get("resource");
            if (resource != null) resources.add(String.valueOf(resource));
        }
        if (!resources.contains("SKILL.md")
                || !resources.contains("references/template.md")) {
            throw new IllegalStateException(
                "Skill 渐进加载不完整，实际加载资源：" + resources
            );
        }
    }
}
