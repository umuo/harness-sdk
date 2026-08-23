package io.github.gitsilence.agent.examples;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.Usage;
import io.github.gitsilence.agent.observability.AgentObservability;

import java.util.concurrent.CompletableFuture;

/** Demonstrates OFF, LOGGING, and PLATFORM observability selection. */
public final class ObservabilityExample {

    private ObservabilityExample() {
    }

    public static void main(String[] args) {
        AgentObservability observability = observabilityFromEnvironment();
        try {
            Agent agent = Agent.builder()
                .name("observable_assistant")
                .description("A deterministic observability example")
                .model(request -> CompletableFuture.completedFuture(
                    new ModelResponse(
                        ChatMessage.assistant("The observable Turn completed."),
                        new Usage(12, 6, 18),
                        null
                    )
                ))
                .plugin(observability)
                .build();

            AgentResult result = agent.run("Create one observable Turn");
            System.out.println(result.getOutput());
            System.out.println("Observability mode: "
                + observability.getMode());
        } finally {
            observability.close();
        }
    }

    private static AgentObservability observabilityFromEnvironment() {
        String mode = environment("AGENT_OBSERVABILITY_MODE", "OFF")
            .trim().toUpperCase();
        switch (mode) {
            case "OFF":
                return AgentObservability.disabled();
            case "LOGGING":
                return AgentObservability.logging();
            case "PLATFORM":
                return AgentObservability.platform(
                    environment(
                        "AGENT_OBSERVABILITY_ENDPOINT",
                        "http://localhost:3000/api/traces"
                    ),
                    environment("AGENT_OBSERVABILITY_API_KEY", "")
                );
            default:
                throw new IllegalArgumentException(
                    "Unsupported AGENT_OBSERVABILITY_MODE: " + mode
                );
        }
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
