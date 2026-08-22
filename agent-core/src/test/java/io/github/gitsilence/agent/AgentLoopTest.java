package io.github.gitsilence.agent;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.MessageRole;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.stream.ModelStream;
import io.github.gitsilence.agent.model.stream.ModelStreamEvent;
import io.github.gitsilence.agent.model.stream.ModelStreamListener;
import io.github.gitsilence.agent.model.stream.StreamingChatModel;
import io.github.gitsilence.agent.runtime.AgentEvent;
import io.github.gitsilence.agent.runtime.AgentEventType;
import io.github.gitsilence.agent.runtime.AgentExecutionException;
import io.github.gitsilence.agent.runtime.ExecutionStatus;
import io.github.gitsilence.agent.runtime.StopSignal;
import io.github.gitsilence.agent.runtime.TerminationCondition;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolDefinition;
import io.github.gitsilence.agent.tool.ToolErrorPolicy;
import io.github.gitsilence.agent.tool.ToolResult;
import io.github.gitsilence.agent.tool.Tools;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {

    @Test
    void returnsFinalAnswerWithoutTools() {
        ScriptedChatModel model = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant("hello"))
        );
        Agent agent = agentBuilder("assistant", model).build();

        AgentResult result = agent.run("say hello");

        assertTrue(result.isCompleted());
        assertEquals("hello", result.getOutput());
        assertEquals(1, result.getState().getStep());
        assertEquals(3, result.getState().getMessages().size());
        assertEquals(MessageRole.SYSTEM, result.getState().getMessages().get(0).getRole());
        assertEquals(MessageRole.USER, result.getState().getMessages().get(1).getRole());
        assertEquals(MessageRole.ASSISTANT, result.getState().getMessages().get(2).getRole());
    }

    @Test
    void executesToolAndReturnsResultToModel() {
        ToolCall call = new ToolCall("call-1", "echo", "{\"text\":\"hello\"}");
        ScriptedChatModel model = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(null, Collections.singletonList(call))),
            ModelResponse.of(ChatMessage.assistant("echoed hello"))
        );
        Tool echo = Tools.sync(
            ToolDefinition.builder()
                .name("echo")
                .description("Echoes text")
                .inputSchema("{\"type\":\"object\",\"properties\":{"
                    + "\"text\":{\"type\":\"string\"}},"
                    + "\"required\":[\"text\"]}")
                .build(),
            (arguments, context) -> ToolResult.success(
                arguments.requireString("text")
            )
        );
        Agent agent = agentBuilder("assistant", model).tool(echo).build();

        AgentResult result = agent.run("echo hello");

        assertEquals("echoed hello", result.getOutput());
        assertEquals(2, result.getState().getStep());
        assertEquals(1, result.getState().getToolResults().size());
        ModelRequest secondRequest = model.getRequests().get(1);
        ChatMessage toolMessage = secondRequest.getMessages()
            .get(secondRequest.getMessages().size() - 1);
        assertEquals(MessageRole.TOOL, toolMessage.getRole());
        assertEquals("call-1", toolMessage.getToolCallId());
        assertEquals("hello", toolMessage.getContent());
    }

    @Test
    void reportsUnknownToolToModelByDefault() {
        ToolCall call = new ToolCall("call-missing", "missing", "{}");
        ScriptedChatModel model = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(null, Collections.singletonList(call))),
            ModelResponse.of(ChatMessage.assistant("I recovered"))
        );
        Agent agent = agentBuilder("assistant", model).build();

        AgentResult result = agent.run("use a missing tool");

        assertEquals("I recovered", result.getOutput());
        assertTrue(result.getState().getToolResults().get(0).getResult().isError());
        assertTrue(result.getState().getToolResults().get(0).getResult()
            .getContent().contains("Unknown tool"));
    }

    @Test
    void stopsAtMaximumModelSteps() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = request -> {
            int current = calls.incrementAndGet();
            ToolCall call = new ToolCall("call-" + current, "again", "{}");
            return CompletableFuture.completedFuture(ModelResponse.of(
                ChatMessage.assistant(null, Collections.singletonList(call))
            ));
        };
        Tool again = Tools.sync(
            ToolDefinition.builder()
                .name("again")
                .description("Requests another model round")
                .build(),
            (arguments, context) -> ToolResult.success("continue")
        );
        Agent agent = agentBuilder("bounded", model)
            .tool(again)
            .maxSteps(2)
            .build();

        AgentResult result = agent.run("loop");

        assertEquals(ExecutionStatus.STOPPED, result.getStatus());
        assertEquals("MAX_STEPS_REACHED", result.getStopReason());
        assertEquals(2, calls.get());
        assertEquals(2, result.getState().getToolResults().size());
    }

    @Test
    void keepsParallelToolResultsInCallOrder() {
        ToolCall slowCall = new ToolCall("slow-call", "slow", "{}");
        ToolCall fastCall = new ToolCall("fast-call", "fast", "{}");
        ScriptedChatModel model = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(
                null, Arrays.asList(slowCall, fastCall)
            )),
            ModelResponse.of(ChatMessage.assistant("done"))
        );
        Tool slow = asyncValueTool("slow", "slow-result", 80L);
        Tool fast = asyncValueTool("fast", "fast-result", 5L);
        Agent agent = agentBuilder("parallel", model)
            .tool(slow)
            .tool(fast)
            .parallelToolCalls(true)
            .build();

        AgentResult result = agent.run("run both");

        assertEquals("slow", result.getState().getToolResults().get(0)
            .getCall().getName());
        assertEquals("fast", result.getState().getToolResults().get(1)
            .getCall().getName());
    }

    @Test
    void childAgentGetsFreshStateAndReturnsOnlyItsResult() {
        ScriptedChatModel childModel = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant("child report"))
        );
        Agent child = agentBuilder("research_agent", childModel)
            .instructions("Child instructions")
            .build();

        ToolCall delegate = new ToolCall(
            "delegate-1",
            "research_agent",
            "{\"task\":\"child-only task\"}"
        );
        ScriptedChatModel parentModel = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(
                null, Collections.singletonList(delegate)
            )),
            ModelResponse.of(ChatMessage.assistant("parent final"))
        );
        Agent parent = agentBuilder("supervisor", parentModel)
            .instructions("Parent secret instructions")
            .tool(child)
            .build();

        AgentResult result = parent.run("parent-only request");

        ModelRequest childRequest = childModel.getRequests().get(0);
        assertEquals(2, childRequest.getMessages().size());
        assertEquals("Child instructions", childRequest.getMessages().get(0).getContent());
        assertEquals("child-only task", childRequest.getMessages().get(1).getContent());
        assertEquals("parent final", result.getOutput());
        assertEquals("child report", result.getState().getToolResults().get(0)
            .getResult().getContent());
        assertNotNull(result.getState().getToolResults().get(0)
            .getResult().getMetadata().get("childRunId"));
        assertNotEquals(
            result.getState().getRunId(),
            result.getState().getToolResults().get(0)
                .getResult().getMetadata().get("childRunId")
        );
    }

    @Test
    void customTerminationConditionCanCompleteAfterToolBatch() {
        ScriptedChatModel model = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(null, Collections.singletonList(
                new ToolCall("mark-call", "mark_done", "{}")
            )))
        );
        Tool markDone = Tools.sync(
            ToolDefinition.builder()
                .name("mark_done")
                .description("Marks work done")
                .build(),
            (arguments, context) -> {
                context.putVariable("done", true);
                return ToolResult.success("marked");
            }
        );
        TerminationCondition done = state -> Boolean.TRUE.equals(
            state.getVariables().get("done")
        )
            ? Optional.of(StopSignal.complete("condition result", "DONE_VARIABLE"))
            : Optional.<StopSignal>empty();
        Agent agent = agentBuilder("conditional", model)
            .tool(markDone)
            .terminationCondition(done)
            .build();

        AgentResult result = agent.run("finish through a condition");

        assertEquals("condition result", result.getOutput());
        assertEquals("DONE_VARIABLE", result.getStopReason());
        assertEquals(1, result.getState().getStep());
    }

    @Test
    void failFastToolErrorPreservesFailedState() {
        ScriptedChatModel model = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(null, Collections.singletonList(
                new ToolCall("fail-call", "fail", "{}")
            )))
        );
        Tool failing = Tools.sync(
            ToolDefinition.builder()
                .name("fail")
                .description("Always fails")
                .build(),
            (arguments, context) -> {
                throw new IllegalStateException("boom");
            }
        );
        Agent agent = agentBuilder("failing", model)
            .tool(failing)
            .toolErrorPolicy(ToolErrorPolicy.FAIL_FAST)
            .build();

        AgentExecutionException error = assertThrows(
            AgentExecutionException.class,
            () -> agent.run("fail")
        );

        assertEquals(ExecutionStatus.FAILED, error.getState().getStatus());
        assertTrue(error.getState().getError().getMessage().contains("boom"));
    }

    @Test
    void reportsJava8ScheduledToolTimeoutToModel() {
        ScriptedChatModel model = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(null, Collections.singletonList(
                new ToolCall("wait-call", "wait_forever", "{}")
            ))),
            ModelResponse.of(ChatMessage.assistant("recovered from timeout"))
        );
        Tool waiting = Tools.async(
            ToolDefinition.builder()
                .name("wait_forever")
                .description("Never completes")
                .build(),
            (arguments, context) -> new CompletableFuture<ToolResult>()
        );
        Agent agent = agentBuilder("timeout", model)
            .tool(waiting)
            .toolTimeout(Duration.ofMillis(20))
            .build();

        AgentResult result = agent.run("wait");

        assertEquals("recovered from timeout", result.getOutput());
        assertTrue(result.getState().getToolResults().get(0)
            .getResult().getContent().contains("timed out"));
    }

    @Test
    void streamsNormalizedModelEventsThroughAgentLifecycle() {
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public CompletableFuture<ModelResponse> generate(ModelRequest request) {
                throw new AssertionError("Streaming execution must use generateStream");
            }

            @Override
            public ModelStream generateStream(ModelRequest request,
                                               ModelStreamListener listener) {
                listener.onEvent(ModelStreamEvent.responseStarted(
                    Collections.singletonMap("responseId", "stream-1")
                ));
                listener.onEvent(ModelStreamEvent.textDelta("hello"));
                ModelResponse response = ModelResponse.of(
                    ChatMessage.assistant("hello")
                );
                listener.onComplete(response);
                return new ModelStream(
                    CompletableFuture.completedFuture(response),
                    () -> { }
                );
            }
        };
        Agent agent = agentBuilder("streaming", model).build();
        List<AgentEvent> events = new ArrayList<AgentEvent>();

        AgentResult result = agent.runStreamingAsync("hello", events::add).join();

        assertEquals("hello", result.getOutput());
        assertEquals(Arrays.asList(
            AgentEventType.TURN_STARTED,
            AgentEventType.STEP_STARTED,
            AgentEventType.MODEL_STARTED,
            AgentEventType.MODEL_STREAM_EVENT,
            AgentEventType.MODEL_STREAM_EVENT,
            AgentEventType.MODEL_COMPLETED,
            AgentEventType.STEP_COMPLETED,
            AgentEventType.TURN_COMPLETED
        ), eventTypes(events));
        assertEquals("hello", events.get(4).getModelStreamEvent().getDelta());
        assertEquals(1L, events.get(0).getSequence());
        assertEquals(8L, events.get(7).getSequence());
    }

    @Test
    void emitsToolLifecycleEventsForNonStreamingFallbackModel() {
        ToolCall call = new ToolCall("call-1", "echo", "{\"text\":\"hello\"}");
        ScriptedChatModel model = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(null, Collections.singletonList(call))),
            ModelResponse.of(ChatMessage.assistant("done"))
        );
        Tool echo = Tools.sync(
            ToolDefinition.builder().name("echo").description("Echo").build(),
            (arguments, context) -> ToolResult.success(arguments.requireString("text"))
        );
        Agent agent = agentBuilder("events", model).tool(echo).build();
        List<AgentEvent> events = new ArrayList<AgentEvent>();

        agent.runStreamingAsync("run", events::add).join();

        List<AgentEventType> types = eventTypes(events);
        assertTrue(types.contains(AgentEventType.TOOL_STARTED));
        assertTrue(types.contains(AgentEventType.TOOL_COMPLETED));
        assertEquals(1, Collections.frequency(types, AgentEventType.TURN_STARTED));
        assertEquals(2, Collections.frequency(types, AgentEventType.STEP_STARTED));
        assertEquals(2, Collections.frequency(types, AgentEventType.STEP_COMPLETED));
        assertEquals(1, Collections.frequency(types, AgentEventType.TURN_COMPLETED));
        AgentEvent completed = events.stream()
            .filter(event -> event.getType() == AgentEventType.TOOL_COMPLETED)
            .findFirst()
            .get();
        assertEquals("hello", completed.getToolExecution().getResult().getContent());
    }

    @Test
    void cancellingAgentCancelsActiveModelStream() {
        AtomicBoolean modelCancelled = new AtomicBoolean();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public CompletableFuture<ModelResponse> generate(ModelRequest request) {
                throw new AssertionError("Streaming execution must use generateStream");
            }

            @Override
            public ModelStream generateStream(ModelRequest request,
                                               ModelStreamListener listener) {
                return new ModelStream(
                    new CompletableFuture<ModelResponse>(),
                    () -> modelCancelled.set(true)
                );
            }
        };
        Agent agent = agentBuilder("cancel", model).build();
        List<AgentEvent> events = new ArrayList<AgentEvent>();

        CompletableFuture<AgentResult> execution =
            agent.runStreamingAsync("wait", events::add);

        assertTrue(execution.cancel(true));
        assertTrue(modelCancelled.get());
        assertEquals(AgentEventType.TURN_CANCELLED,
            events.get(events.size() - 1).getType());
    }

    @Test
    void cancellingParentCancelsActiveChildAgent() {
        CompletableFuture<ModelResponse> childModelFuture =
            new CompletableFuture<ModelResponse>();
        Agent child = agentBuilder("child", request -> childModelFuture).build();
        ScriptedChatModel parentModel = new ScriptedChatModel(
            ModelResponse.of(ChatMessage.assistant(null, Collections.singletonList(
                new ToolCall("delegate-1", "child", "{\"task\":\"wait\"}")
            )))
        );
        Agent parent = agentBuilder("parent", parentModel).tool(child).build();

        CompletableFuture<AgentResult> execution = parent.runAsync("delegate");

        assertTrue(execution.cancel(true));
        assertTrue(childModelFuture.isCancelled());
    }

    private static List<AgentEventType> eventTypes(List<AgentEvent> events) {
        List<AgentEventType> types = new ArrayList<AgentEventType>();
        for (AgentEvent event : events) {
            types.add(event.getType());
        }
        return types;
    }

    private static AgentBuilderAdapter agentBuilder(String name, ChatModel model) {
        return new AgentBuilderAdapter(name, model);
    }

    private static Tool asyncValueTool(String name, String value, long delayMillis) {
        return Tools.async(
            ToolDefinition.builder()
                .name(name)
                .description("Returns " + value)
                .build(),
            (arguments, context) -> CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return ToolResult.success(value);
            }, context.getExecutor())
        );
    }

    private static final class AgentBuilderAdapter {
        private final io.github.gitsilence.agent.agent.AgentBuilder delegate;

        private AgentBuilderAdapter(String name, ChatModel model) {
            this.delegate = Agent.builder()
                .name(name)
                .description("Test agent " + name)
                .instructions("Test instructions")
                .model(model);
        }

        private AgentBuilderAdapter instructions(String instructions) {
            delegate.instructions(instructions);
            return this;
        }

        private AgentBuilderAdapter tool(Tool tool) {
            delegate.tool(tool);
            return this;
        }

        private AgentBuilderAdapter tool(Agent agent) {
            delegate.tool(agent);
            return this;
        }

        private AgentBuilderAdapter maxSteps(int maxSteps) {
            delegate.maxSteps(maxSteps);
            return this;
        }

        private AgentBuilderAdapter parallelToolCalls(boolean value) {
            delegate.parallelToolCalls(value);
            return this;
        }

        private AgentBuilderAdapter toolErrorPolicy(ToolErrorPolicy value) {
            delegate.toolErrorPolicy(value);
            return this;
        }

        private AgentBuilderAdapter toolTimeout(Duration value) {
            delegate.toolTimeout(value);
            return this;
        }

        private AgentBuilderAdapter terminationCondition(TerminationCondition condition) {
            delegate.terminationCondition(condition);
            return this;
        }

        private Agent build() {
            return delegate.build();
        }
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<ModelResponse> responses = new ArrayDeque<ModelResponse>();
        private final List<ModelRequest> requests = new ArrayList<ModelRequest>();

        private ScriptedChatModel(ModelResponse... responses) {
            this.responses.addAll(Arrays.asList(responses));
        }

        @Override
        public synchronized CompletableFuture<ModelResponse> generate(ModelRequest request) {
            requests.add(request);
            if (responses.isEmpty()) {
                CompletableFuture<ModelResponse> failed =
                    new CompletableFuture<ModelResponse>();
                failed.completeExceptionally(new AssertionError("No scripted response"));
                return failed;
            }
            return CompletableFuture.completedFuture(responses.removeFirst());
        }

        private List<ModelRequest> getRequests() {
            return requests;
        }
    }
}
