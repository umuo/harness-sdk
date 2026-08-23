package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;
import io.github.gitsilence.agent.tools.builtin.WorkspaceTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Runs read_file, write_file, edit, glob, and Bash in a bounded workspace. */
public final class BuiltInToolsAgentExample {

    private static final Set<String> EXPECTED_TOOLS = new LinkedHashSet<String>(
        Arrays.asList("glob", "read_file", "write_file", "edit", "bash")
    );

    private BuiltInToolsAgentExample() {
    }

    public static void main(String[] args) throws IOException {
        Path workspace = prepareWorkspace();
        WorkspaceTools tools = WorkspaceTools.builder(workspace)
            .enableBash(true)
            .build();
        OpenAiChatModel model = ExampleSupport.realModel();

        try (AgentObservability observability =
                 ExampleSupport.observability("built-in-tools")) {
            Agent agent = Agent.builder()
                .name("workspace_agent")
                .description("在隔离工作目录中验证全部内置文件和 Bash 工具")
                .instructions(
                    "必须严格按顺序完成任务，并且实际调用 glob、read_file、write_file、edit、bash。"
                        + "不要用 Bash 替代专用文件工具。"
                        + tools.getInstructions()
                        + "所有解释和最终答案都使用中文。"
                )
                .model(model)
                .tools(tools.getTools())
                .toolTimeout(Duration.ofSeconds(70))
                .maxSteps(14)
                .plugin(observability)
                .build();

            AgentResult result = agent.run(ExampleSupport.task(
                args,
                "请完成以下操作：1）用 glob 查找工作区中的 txt 文件；"
                    + "2）用 read_file 阅读 source.txt；"
                    + "3）用 write_file 创建 report.md，内容必须包含 source.txt 的摘要和独占一行的“状态：待复核”；"
                    + "4）用 read_file 读取 report.md；"
                    + "5）用 edit 把“状态：待复核”精确替换为“状态：已复核”；"
                    + "6）用 bash 执行 wc -l report.md；"
                    + "7）最后再用 read_file 验证 report.md，并汇报每个工具的结果。"
            ));
            verifyTools(result);
            ExampleSupport.printResult(result);
            System.out.println("工作目录：" + workspace);
        }
    }

    private static Path prepareWorkspace() throws IOException {
        String configured = System.getenv("AGENT_EXAMPLE_WORKSPACE");
        Path workspace = configured == null || configured.trim().isEmpty()
            ? ExampleSupport.repositoryRoot().resolve(
                "agent-examples/target/builtin-tools-workspace"
            )
            : java.nio.file.Paths.get(configured).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.write(
            workspace.resolve("source.txt"),
            Arrays.asList(
                "Agent Loop 围绕统一 State 推进。",
                "Tool 是一等公民，Agent 也可以作为 Tool。",
                "所有外部调用都应该进入可观测调用链。"
            ),
            StandardCharsets.UTF_8
        );
        Files.deleteIfExists(workspace.resolve("report.md"));
        return workspace;
    }

    private static void verifyTools(AgentResult result) {
        Set<String> used = new LinkedHashSet<String>();
        for (ToolExecutionRecord record : result.getState().getToolResults()) {
            used.add(record.getCall().getName());
        }
        if (!used.containsAll(EXPECTED_TOOLS)) {
            Set<String> missing = new LinkedHashSet<String>(EXPECTED_TOOLS);
            missing.removeAll(used);
            throw new IllegalStateException(
                "真实模型没有调用全部内置工具，缺少：" + missing + "，实际调用：" + used
            );
        }
    }
}
