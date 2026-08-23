package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.mcp.McpToolSet;
import io.github.gitsilence.agent.mcp.StdioMcpClient;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.openai.OpenAiChatModel;
import io.github.gitsilence.agent.runtime.Futures;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/** Discovers a real filesystem MCP server and lets a real LLM call its Tools. */
public final class McpAgentExample {

    private McpAgentExample() {
    }

    public static void main(String[] args) {
        String configuredWorkspace = System.getenv("MCP_WORKSPACE");
        Path workspace = configuredWorkspace == null
                || configuredWorkspace.trim().isEmpty()
            ? ExampleSupport.repositoryRoot()
            : Paths.get(configuredWorkspace).toAbsolutePath().normalize();
        StdioMcpClient client = StdioMcpClient.builder(
                ExampleSupport.environment("MCP_COMMAND", "npx"))
            .arguments(
                "-y",
                ExampleSupport.environment(
                    "MCP_FILESYSTEM_PACKAGE",
                    "@modelcontextprotocol/server-filesystem"
                ),
                workspace.toString()
            )
            .requestTimeout(Duration.ofSeconds(30))
            .build();
        OpenAiChatModel model = ExampleSupport.realModel();

        try (AgentObservability observability =
                 ExampleSupport.observability("mcp-filesystem");
             McpToolSet filesystem = Futures.join(
                 McpToolSet.discover(client, "filesystem")
             )) {
            Agent agent = Agent.builder()
                .name("mcp_workspace_agent")
                .description("通过真实 MCP 文件系统服务器读取并分析项目")
                .instructions(
                    "必须调用 filesystem 命名空间中的 MCP 工具完成任务。"
                        + "本示例只允许读取和列目录，不得创建、修改、移动或删除任何文件。"
                        + "最终答案使用中文，并说明实际调用了哪些 MCP 工具。"
                )
                .model(model)
                .tools(filesystem.getTools())
                .toolTimeout(Duration.ofSeconds(40))
                .maxSteps(8)
                .plugin(observability)
                .build();

            System.out.println("MCP 协议版本："
                + filesystem.getInitializeResult().getProtocolVersion());
            System.out.println("MCP 工具映射："
                + filesystem.getLocalToRemoteNames());
            AgentResult result = agent.run(ExampleSupport.task(
                args,
                "请先列出工作区根目录，再读取根目录 pom.xml，"
                    + "总结这个 Maven 项目包含哪些模块以及每个模块的职责。"
            ));
            verifyMcpCall(result);
            ExampleSupport.printResult(result);
        }
    }

    private static void verifyMcpCall(AgentResult result) {
        for (ToolExecutionRecord record : result.getState().getToolResults()) {
            if (record.getCall().getName().startsWith("filesystem__")) {
                return;
            }
        }
        throw new IllegalStateException("真实模型没有调用 filesystem MCP 工具");
    }
}
