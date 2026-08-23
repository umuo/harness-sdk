package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.observability.AgentObservability;
import io.github.gitsilence.agent.observability.PlatformTraceExporter;
import io.github.gitsilence.agent.openai.OpenAiChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Shared real-Provider and observability configuration for examples. */
final class ExampleSupport {

    private ExampleSupport() {
    }

    static OpenAiChatModel realModel() {
        return OpenAiChatModel.builder()
            .apiKey(requireEnvironment("OPENAI_API_KEY"))
            .model(requireEnvironment("LLM_MODEL"))
            .baseUrl(requireEnvironment("LLM_BASE_URL"))
            .build();
    }

    static AgentObservability observability(String exampleName) {
        PlatformTraceExporter exporter = PlatformTraceExporter.builder(
                environment(
                    "AGENT_OBSERVABILITY_ENDPOINT",
                    "http://localhost:3000/api/traces"
                ))
            .apiKey(environment("AGENT_OBSERVABILITY_API_KEY", ""))
            .build();
        return AgentObservability.builder()
            .captureContent(true)
            .attribute("example.name", exampleName)
            .platform(exporter)
            .build();
    }

    static String task(String[] arguments, String defaultTask) {
        return arguments == null || arguments.length == 0
            ? defaultTask
            : String.join(" ", arguments);
    }

    static void printResult(AgentResult result) {
        System.out.println("\n\n===== 最终结果 =====");
        System.out.println(result.getOutput());
        System.out.println("===== 执行状态：" + result.getStatus() + " =====");
        System.out.println("Turn ID：" + result.getState().getTurnId());
    }

    static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("缺少环境变量：" + name);
        }
        return value;
    }

    static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    static Path repositoryRoot() {
        Path current = Paths.get(".").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("agent-core"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("agent-core"))) {
            return parent;
        }
        return current;
    }
}
