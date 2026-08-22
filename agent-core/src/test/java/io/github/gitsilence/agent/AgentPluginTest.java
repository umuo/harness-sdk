package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.plugin.AgentPlugin;
import io.github.gitsilence.agent.plugin.ModelInterceptor;
import io.github.gitsilence.agent.plugin.ToolInterceptor;
import io.github.gitsilence.agent.runtime.AgentEvent;
import io.github.gitsilence.agent.runtime.AgentEventType;
import io.github.gitsilence.agent.runtime.AgentExecutionException;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.Tools;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentPluginTest {

    @Test
    void modelInterceptorsWrapInPluginRegistrationOrder() {
        List<String> order = new ArrayList<String>();
        ChatModel model = request -> {
            order.add("model");
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("ok"))
            );
        };
        Agent agent = agent("model-plugins", model)
            .plugin(modelPlugin("a", order))
            .plugin(modelPlugin("b", order))
            .build();

        AgentResult result = agent.run("hello");

        assertEquals("ok", result.getOutput());
        assertEquals(Arrays.asList(
            "a-before", "b-before", "model", "b-after", "a-after"
        ), order);
    }

    @Test
    void modelInterceptorCanShortCircuitModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = request -> {
            modelCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("model"))
            );
        };
        AgentPlugin cache = new AgentPlugin() {
            @Override
            public String name() {
                return "cache";
            }

            @Override
            public List<ModelInterceptor> modelInterceptors() {
                return Collections.singletonList((invocation, chain) ->
                    CompletableFuture.completedFuture(
                        ModelResponse.of(ChatMessage.assistant("cached"))
                    )
                );
            }
        };
        Agent agent = agent("cached", model).plugin(cache).build();

        AgentResult result = agent.run("hello");

        assertEquals("cached", result.getOutput());
        assertEquals(0, modelCalls.get());
    }

    @Test
    void modelInterceptorCanRewriteModelRequest() {
        AtomicReference<String> modelInput = new AtomicReference<String>();
        ChatModel model = request -> {
            modelInput.set(request.getMessages()
                .get(request.getMessages().size() - 1).getContent());
            return CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("done"))
            );
        };
        AgentPlugin rewrite = new AgentPlugin() {
            @Override
            public String name() {
                return "request-rewrite";
            }

            @Override
            public List<ModelInterceptor> modelInterceptors() {
                return Collections.singletonList((invocation, chain) -> {
                    List<ChatMessage> messages = new ArrayList<ChatMessage>(
                        invocation.getRequest().getMessages()
                    );
                    messages.set(messages.size() - 1, ChatMessage.user("rewritten"));
                    ModelRequest request = new ModelRequest(
                        messages,
                        invocation.getRequest().getTools(),
                        invocation.getRequest().getOptions()
                    );
                    return chain.proceed(invocation.withRequest(request));
                });
            }
        };
        Agent agent = agent("rewrite", model).plugin(rewrite).build();

        agent.run("original");

        assertEquals("rewritten", modelInput.get());
    }

    @Test
    void toolInterceptorCanRewriteArgumentsAndPostProcessResult() {
        AtomicReference<String> executedArgument = new AtomicReference<String>();
        AtomicInteger modelRound = new AtomicInteger();
        ChatModel model = request -> {
            if (modelRound.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall(
                            "call-1", "echo", "{\"text\":\"original\"}"
                        )
                    ))
                ));
            }
            ChatMessage toolMessage = request.getMessages()
                .get(request.getMessages().size() - 1);
            return CompletableFuture.completedFuture(ModelResponse.of(
                ChatMessage.assistant(toolMessage.getContent())
            ));
        };
        Tool echo = Tools.sync(
            ToolDefinition.builder().name("echo").description("Echo").build(),
            (arguments, context) -> {
                String value = arguments.requireString("text");
                executedArgument.set(value);
                return ToolResult.success(value);
            }
        );
        AgentPlugin plugin = new AgentPlugin() {
            @Override
            public String name() {
                return "tool-rewrite";
            }

            @Override
            public List<ToolInterceptor> toolInterceptors() {
                return Collections.singletonList((invocation, chain) -> {
                    ToolCall original = invocation.getCall();
                    ToolCall rewritten = new ToolCall(
                        original.getId(), original.getName(),
                        "{\"text\":\"changed\"}"
                    );
                    return chain.proceed(invocation.withCall(rewritten))
                        .thenApply(result -> ToolResult.success(
                            result.getContent() + "-post"
                        ));
                });
            }
        };
        Agent agent = agent("tool-plugin", model)
            .tool(echo)
            .plugin(plugin)
            .build();

        AgentResult result = agent.run("echo");

        assertEquals("changed", executedArgument.get());
        assertEquals("changed-post", result.getOutput());
        assertEquals("{\"text\":\"original\"}",
            result.getState().getToolResults().get(0).getCall().getArguments());
        assertEquals("{\"text\":\"changed\"}",
            result.getState().getToolResults().get(0)
                .getExecutedCall().getArguments());
    }

    @Test
    void toolInterceptorCanShortCircuitToolExecution() {
        AtomicBoolean toolExecuted = new AtomicBoolean();
        AtomicInteger round = new AtomicInteger();
        ChatModel model = request -> {
            if (round.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(ModelResponse.of(
                    ChatMessage.assistant(null, Collections.singletonList(
                        new ToolCall("call-1", "dangerous", "{}")
                    ))
                ));
            }
            ChatMessage toolMessage = request.getMessages()
                .get(request.getMessages().size() - 1);
            return CompletableFuture.completedFuture(ModelResponse.of(
                ChatMessage.assistant(toolMessage.getContent())
            ));
        };
        Tool dangerous = Tools.sync(
            ToolDefinition.builder()
                .name("dangerous")
                .description("Must not execute")
                .build(),
            (arguments, context) -> {
                toolExecuted.set(true);
                return ToolResult.success("executed");
            }
        );
        AgentPlugin guard = new AgentPlugin() {
            @Override
            public String name() {
                return "guard";
            }

            @Override
            public List<ToolInterceptor> toolInterceptors() {
                return Collections.singletonList((invocation, chain) ->
                    CompletableFuture.completedFuture(
                        ToolResult.failure("blocked by policy")
                    )
                );
            }
        };
        Agent agent = agent("guarded", model)
            .tool(dangerous)
            .plugin(guard)
            .build();

        AgentResult result = agent.run("run dangerous tool");

        assertFalse(toolExecuted.get());
        assertEquals("blocked by policy", result.getOutput());
        assertTrue(result.getState().getToolResults().get(0).getResult().isError());
    }

    @Test
    void pluginObservesTurnAndStepFactsWithoutControllingExecution() {
        List<AgentEventType> observed = new ArrayList<AgentEventType>();
        AgentPlugin observer = new AgentPlugin() {
            @Override
            public String name() {
                return "observer";
            }

            @Override
            public void onEvent(AgentEvent event) {
                observed.add(event.getType());
                if (event.getType() == AgentEventType.STEP_STARTED) {
                    throw new IllegalStateException("observer failure is isolated");
                }
            }
        };
        Agent agent = agent("observed", request ->
            CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("done"))
            )
        ).plugin(observer).build();

        AgentResult result = agent.run("hello");

        assertEquals("done", result.getOutput());
        assertEquals(Arrays.asList(
            AgentEventType.TURN_STARTED,
            AgentEventType.STEP_STARTED,
            AgentEventType.MODEL_STARTED,
            AgentEventType.MODEL_COMPLETED,
            AgentEventType.STEP_COMPLETED,
            AgentEventType.TURN_COMPLETED
        ), observed);
    }

    @Test
    void cancellationCrossesModelInterceptorChain() {
        CompletableFuture<ModelResponse> modelFuture =
            new CompletableFuture<ModelResponse>();
        AgentPlugin wrapper = new AgentPlugin() {
            @Override
            public String name() {
                return "wrapper";
            }

            @Override
            public List<ModelInterceptor> modelInterceptors() {
                return Collections.singletonList((invocation, chain) ->
                    chain.proceed(invocation).thenApply(response -> response)
                );
            }
        };
        Agent agent = agent("cancel-plugin", request -> modelFuture)
            .plugin(wrapper)
            .build();

        CompletableFuture<AgentResult> execution = agent.runAsync("wait");

        assertTrue(execution.cancel(true));
        assertTrue(modelFuture.isCancelled());
    }

    @Test
    void cancellationCrossesToolInterceptorChain() {
        CompletableFuture<ToolResult> toolFuture =
            new CompletableFuture<ToolResult>();
        ChatModel model = request -> CompletableFuture.completedFuture(
            ModelResponse.of(ChatMessage.assistant(
                null,
                Collections.singletonList(
                    new ToolCall("call-1", "wait", "{}")
                )
            ))
        );
        Tool waiting = Tools.async(
            ToolDefinition.builder().name("wait").description("Wait").build(),
            (arguments, context) -> toolFuture
        );
        AgentPlugin wrapper = new AgentPlugin() {
            @Override
            public String name() {
                return "tool-wrapper";
            }

            @Override
            public List<ToolInterceptor> toolInterceptors() {
                return Collections.singletonList((invocation, chain) ->
                    chain.proceed(invocation).thenApply(result -> result)
                );
            }
        };
        Agent agent = agent("cancel-tool-plugin", model)
            .tool(waiting)
            .plugin(wrapper)
            .build();

        CompletableFuture<AgentResult> execution = agent.runAsync("wait");

        assertTrue(execution.cancel(true));
        assertTrue(toolFuture.isCancelled());
    }

    @Test
    void modelInterceptorFailureProducesFailedTurnEvent() {
        List<AgentEventType> events = new ArrayList<AgentEventType>();
        AgentPlugin failing = new AgentPlugin() {
            @Override
            public String name() {
                return "failing-model-hook";
            }

            @Override
            public void onEvent(AgentEvent event) {
                events.add(event.getType());
            }

            @Override
            public List<ModelInterceptor> modelInterceptors() {
                return Collections.singletonList((invocation, chain) -> {
                    throw new IllegalStateException("plugin denied model call");
                });
            }
        };
        Agent agent = agent("plugin-failure", request ->
            CompletableFuture.completedFuture(
                ModelResponse.of(ChatMessage.assistant("unused"))
            )
        ).plugin(failing).build();

        AgentExecutionException error = assertThrows(
            AgentExecutionException.class,
            () -> agent.run("hello")
        );

        assertTrue(error.getCause().getMessage().contains("plugin denied"));
        assertEquals(AgentEventType.TURN_FAILED, events.get(events.size() - 1));
    }

    private static AgentPlugin modelPlugin(String name, List<String> order) {
        return new AgentPlugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<ModelInterceptor> modelInterceptors() {
                return Collections.singletonList((invocation, chain) -> {
                    order.add(name + "-before");
                    return chain.proceed(invocation).thenApply(response -> {
                        order.add(name + "-after");
                        return response;
                    });
                });
            }
        };
    }

    private static io.github.gitsilence.agent.agent.AgentBuilder agent(
            String name,
            ChatModel model) {
        return Agent.builder()
            .name(name)
            .description(name)
            .model(model);
    }
}
