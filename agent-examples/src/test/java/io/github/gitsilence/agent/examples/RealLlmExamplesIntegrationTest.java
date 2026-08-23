package io.github.gitsilence.agent.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Explicit, billable integration tests. Every enabled test calls a real LLM;
 * none of these tests installs a fake Model or MCP client.
 */
class RealLlmExamplesIntegrationTest {

    @BeforeEach
    void requireExplicitOptIn() {
        assumeTrue(
            enabled("RUN_REAL_LLM_EXAMPLES"),
            "设置 RUN_REAL_LLM_EXAMPLES=true 后才执行真实 LLM 集成测试"
        );
    }

    @Test
    void delegatesComplexTaskToRealSubAgents() {
        ComplexTaskDelegationExample.main(new String[0]);
    }

    @Test
    void exercisesTodoLifecycleWithRealModel() {
        TodoAgentExample.main(new String[0]);
    }

    @Test
    void exercisesEveryBuiltInToolWithRealModel() throws Exception {
        BuiltInToolsAgentExample.main(new String[0]);
    }

    @Test
    void receivesRealStreamingDeltas() {
        StreamingAgentExample.main(new String[0]);
    }

    @Test
    void progressivelyLoadsSkillAndReferenceWithRealModel() {
        SkillsAgentExample.main(new String[0]);
    }

    @Test
    void discoversAndCallsRealMcpServerWithRealModel() {
        assumeTrue(
            enabled("RUN_MCP_EXAMPLE"),
            "MCP 测试还需要设置 RUN_MCP_EXAMPLE=true，并安装 Node.js/npx"
        );
        McpAgentExample.main(new String[0]);
    }

    private static boolean enabled(String name) {
        return "true".equalsIgnoreCase(System.getenv(name));
    }
}
