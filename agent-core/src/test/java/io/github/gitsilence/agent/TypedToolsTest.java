package io.github.gitsilence.agent;

import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.runtime.AgentRunner;
import io.github.gitsilence.agent.runtime.InvocationPath;
import io.github.gitsilence.agent.state.AgentState;
import io.github.gitsilence.agent.tool.AbstractAsyncTool;
import io.github.gitsilence.agent.tool.AbstractTool;
import io.github.gitsilence.agent.tool.AnnotatedTools;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolArguments;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedToolsTest {

    @Test
    void typedBaseToolGeneratesSchemaAndBindsRenamedFields() {
        Tool tool = new GreetingTool();
        String schema = tool.definition().getInputSchema();

        assertTrue(schema.contains("\"user_name\""));
        assertTrue(schema.contains("Name to greet"));
        assertTrue(schema.contains("\"required\":[\"user_name\"]"));
        assertTrue(schema.contains("\"additionalProperties\":false"));

        ToolResult result = tool.execute(
            ToolArguments.parse("{\"user_name\":\"Ada\"}"), context()
        ).join();

        assertEquals("Hello Ada!", result.getContent());
    }

    @Test
    void typedAsyncToolReturnsItsFutureWithoutBlocking() {
        Tool tool = new AsyncEchoTool();

        ToolResult result = tool.execute(
            ToolArguments.parse("{\"text\":\"ready\"}"), context()
        ).join();

        assertEquals("ready", result.getContent());
    }

    @Test
    void typedBaseToolRejectsMissingRequiredField() {
        Tool tool = new GreetingTool();

        assertThrows(RuntimeException.class, () ->
            tool.execute(ToolArguments.parse("{}"), context()).join()
        );
    }

    @Test
    void typedBaseToolRejectsUnknownField() {
        Tool tool = new GreetingTool();

        assertThrows(RuntimeException.class, () -> tool.execute(
            ToolArguments.parse("{\"user_name\":\"Ada\",\"surprise\":true}"),
            context()
        ).join());
    }

    @Test
    void typedBaseToolDoesNotCoerceWrongJsonTypes() {
        Tool tool = new GreetingTool();

        assertThrows(RuntimeException.class, () -> tool.execute(
            ToolArguments.parse("{\"user_name\":42}"), context()
        ).join());
    }

    @Test
    void annotatedToolInjectsContextAndSupportsOptionalAsyncParameters() {
        ToolContext context = context();
        context.putVariable("prefix", "turn");
        Tool tool = AnnotatedTools.from(new ContextTools()).get(0);
        String schema = tool.definition().getInputSchema();

        assertTrue(schema.contains("\"text\""));
        assertTrue(schema.contains("\"prefix\""));
        assertFalse(schema.contains("ToolContext"));
        assertTrue(schema.contains("\"required\":[\"text\"]"));

        ToolResult result = tool.execute(
            ToolArguments.parse("{\"text\":\"ok\"}"), context
        ).join();

        assertEquals("turn:ok", result.getContent());
    }

    @Test
    void annotationsSupportDescriptionShorthand() {
        Tool tool = AnnotatedTools.from(new PrivateShorthandTools()).get(0);

        assertEquals("Echoes text", tool.definition().getDescription());
        assertTrue(tool.definition().getInputSchema().contains("Text to echo"));
    }

    @Test
    void annotatedToolNamesMustBeUnique() {
        assertThrows(
            IllegalArgumentException.class,
            () -> AnnotatedTools.from(new DuplicateTools())
        );
    }

    @Test
    void cancellingAnnotatedAsyncToolCancelsItsReturnedFuture() {
        CancellableTools target = new CancellableTools();
        Tool tool = AnnotatedTools.from(target).get(0);
        CompletableFuture<ToolResult> execution = tool.execute(
            ToolArguments.parse("{}"), context()
        );
        target.called.join();

        execution.cancel(true);

        assertTrue(target.operation.isCancelled());
    }

    private static ToolContext context() {
        AgentState state = new AgentState(
            "turn-1",
            "test-agent",
            Collections.<ChatMessage>emptyList(),
            Collections.<String, Object>emptyMap(),
            Collections.<String, Object>emptyMap()
        );
        return new ToolContext(
            "call-1",
            state,
            AgentRunner.shared(),
            InvocationPath.root("agent-1", "test-agent")
        );
    }

    private static final class GreetingTool extends AbstractTool<GreetingInput> {

        private GreetingTool() {
            super("greet", "Greets one user", GreetingInput.class);
        }

        @Override
        protected ToolResult execute(GreetingInput input, ToolContext context) {
            return ToolResult.success(
                "Hello " + input.userName + input.punctuation
            );
        }
    }

    public static final class GreetingInput {
        @ToolParam(name = "user_name", description = "Name to greet")
        private String userName;

        @ToolParam(description = "Trailing punctuation", required = false)
        private String punctuation = "!";
    }

    private static final class AsyncEchoTool extends AbstractAsyncTool<EchoInput> {

        private AsyncEchoTool() {
            super("async_echo", "Returns text asynchronously", EchoInput.class);
        }

        @Override
        protected CompletableFuture<ToolResult> executeAsync(
                EchoInput input,
                ToolContext context) {
            return CompletableFuture.completedFuture(
                ToolResult.success(input.text)
            );
        }
    }

    public static final class EchoInput {
        public String text;
    }

    public static final class ContextTools {

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "describe",
            description = "Describes text"
        )
        public CompletableFuture<String> describe(
                @ToolParam(description = "Text to describe") String text,
                @ToolParam(description = "Optional caller prefix") Optional<String> prefix,
                ToolContext context) {
            String effective = prefix.orElse(
                String.valueOf(context.variable("prefix").orElse("none"))
            );
            return CompletableFuture.completedFuture(effective + ":" + text);
        }
    }

    public static final class DuplicateTools {

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "same", description = "First"
        )
        public String first() {
            return "first";
        }

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "same", description = "Second"
        )
        public String second() {
            return "second";
        }
    }

    private static final class PrivateShorthandTools {

        @io.github.gitsilence.agent.tool.annotation.Tool("Echoes text")
        public String echo(@ToolParam("Text to echo") String text) {
            return text;
        }
    }

    public static final class CancellableTools {
        private final CompletableFuture<Void> called =
            new CompletableFuture<Void>();
        private final CompletableFuture<String> operation =
            new CompletableFuture<String>();

        @io.github.gitsilence.agent.tool.annotation.Tool(
            name = "wait_for_result", description = "Waits for a result"
        )
        public CompletableFuture<String> waitForResult() {
            called.complete(null);
            return operation;
        }
    }
}
