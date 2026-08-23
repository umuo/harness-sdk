package io.github.gitsilence.agent.runtime;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.model.stream.ModelStream;
import io.github.gitsilence.agent.model.stream.ModelStreamListener;
import io.github.gitsilence.agent.model.stream.StreamingChatModel;
import io.github.gitsilence.agent.plugin.AgentPlugin;
import io.github.gitsilence.agent.plugin.ModelInterceptor;
import io.github.gitsilence.agent.plugin.ModelInvocation;
import io.github.gitsilence.agent.plugin.ToolInvocation;
import io.github.gitsilence.agent.state.AgentState;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;
import io.github.gitsilence.agent.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class AgentLoop {

    private final Agent agent;
    private final AgentState state;
    private final AgentRunner runner;
    private final InvocationPath path;
    private final ToolExecutor toolExecutor;
    private final AgentEventListener listener;
    private final boolean streamModel;
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicReference<Runnable> activeCancellation =
        new AtomicReference<Runnable>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminalEventEmitted = new AtomicBoolean();

    AgentLoop(Agent agent,
              AgentState state,
              AgentRunner runner,
              InvocationPath path,
              AgentEventListener listener,
              boolean streamModel) {
        this.agent = agent;
        this.state = state;
        this.runner = runner;
        this.path = path;
        this.listener = listener;
        this.streamModel = streamModel;
        this.toolExecutor = new ToolExecutor(
            agent.getToolRegistry(),
            agent.getToolExecutionMode(),
            agent.getToolErrorPolicy(),
            agent.getToolTimeout(),
            agent.getToolResultPolicy(),
            agent.getToolInterceptors()
        );
    }

    CompletableFuture<AgentResult> run() {
        state.start();
        emit(AgentEvent.lifecycle(
            nextSequence(), AgentEventType.TURN_STARTED,
            state.getRunId(), agent.descriptor().getName(), state.getStep(),
            state.snapshot(), null
        ));
        final CompletableFuture<AgentResult> workflow;
        try {
            workflow = iterate();
        } catch (Throwable error) {
            return fail(error);
        }

        CompletableFuture<AgentResult> result = new CompletableFuture<AgentResult>();
        workflow.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                return;
            }
            if (error == null) {
                emitTerminal(value);
                result.complete(value);
            } else {
                Throwable actual = Futures.unwrap(error);
                state.fail(actual);
                AgentExecutionException executionError = new AgentExecutionException(
                    "Agent '" + agent.descriptor().getName() + "' failed",
                    actual,
                    state.snapshot()
                );
                emitFailed(actual);
                result.completeExceptionally(executionError);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                cancelled.set(true);
                state.cancel();
                cancelActive();
                workflow.cancel(true);
                emitCancelled();
            }
        });
        return result;
    }

    private CompletableFuture<AgentResult> iterate() {
        Optional<AgentResult> terminated = evaluateTermination();
        if (terminated.isPresent()) {
            return CompletableFuture.completedFuture(terminated.get());
        }
        if (state.getStep() >= agent.getMaxSteps()) {
            state.stop("MAX_STEPS_REACHED");
            return CompletableFuture.completedFuture(new AgentResult(state.snapshot()));
        }

        state.beginStep();
        emit(AgentEvent.lifecycle(
            nextSequence(), AgentEventType.STEP_STARTED,
            state.getRunId(), agent.descriptor().getName(), state.getStep(),
            null, null
        ));
        ModelRequest request = new ModelRequest(
            state.messagesSnapshot(),
            new ArrayList<>(agent.getToolRegistry().definitions()),
            agent.getModelOptions(),
            capturesModelExchange()
        );
        emit(AgentEvent.modelStarted(
            nextSequence(), state.getRunId(), agent.descriptor().getName(),
            state.getStep(), request
        ));

        final CompletableFuture<ModelResponse> modelFuture = invokeModel(request);

        return modelFuture.thenCompose(response -> {
            if (response == null) {
                return Futures.failed(new IllegalStateException("Model returned null response"));
            }
            ChatMessage assistant = response.getAssistantMessage();
            state.appendMessage(assistant);
            emit(AgentEvent.modelCompleted(
                nextSequence(), state.getRunId(), agent.descriptor().getName(),
                state.getStep(), response
            ));

            if (assistant.getToolCalls().isEmpty()) {
                if (assistant.getContent() == null) {
                    return Futures.failed(new IllegalStateException(
                        "Final assistant message has null content"
                    ));
                }
                emitStepCompleted();
                state.complete(assistant.getContent());
                return CompletableFuture.completedFuture(
                    new AgentResult(state.snapshot())
                );
            }

            for (ToolCall call : assistant.getToolCalls()) {
                emit(AgentEvent.toolStarted(
                    nextSequence(), state.getRunId(), agent.descriptor().getName(),
                    state.getStep(), call
                ));
            }
            CompletableFuture<List<ToolExecutionRecord>> tools = toolExecutor.executeAll(
                assistant.getToolCalls(),
                call -> new ToolContext(call.getId(), state, runner, path),
                call -> new ToolInvocation(
                    state.getRunId(),
                    agent.descriptor().getName(),
                    state.getStep(),
                    call,
                    state.snapshot()
                )
            );
            Runnable toolCancellation = () -> tools.cancel(true);
            activate(toolCancellation);
            tools.whenComplete((records, error) -> clearActive(toolCancellation));
            return tools.thenCompose(records -> {
                appendToolResults(records);
                emitStepCompleted();
                Optional<AgentResult> stop = evaluateTermination();
                if (stop.isPresent()) {
                    return CompletableFuture.completedFuture(stop.get());
                }
                return iterate();
            });
        });
    }

    private void appendToolResults(List<ToolExecutionRecord> records) {
        for (ToolExecutionRecord record : records) {
            state.appendToolExecution(record);
            state.appendMessage(ChatMessage.tool(
                record.getCall().getId(),
                record.getCall().getName(),
                record.getResult().getContent(),
                record.getResult().isError()
            ));
            emit(AgentEvent.toolCompleted(
                nextSequence(), state.getRunId(), agent.descriptor().getName(),
                state.getStep(), record
            ));
        }
    }

    private CompletableFuture<ModelResponse> invokeModel(ModelRequest request) {
        ModelInvocation invocation = new ModelInvocation(
            state.getRunId(),
            agent.descriptor().getName(),
            state.getStep(),
            request,
            state.snapshot(),
            streamModel && agent.getModel() instanceof StreamingChatModel
        );
        CancellationGroup cancellations = new CancellationGroup();
        Runnable cancellation = cancellations::cancel;
        activate(cancellation);
        CompletableFuture<ModelResponse> result = proceedModel(
            invocation, 0, cancellations
        );
        cancellations.add(() -> result.cancel(true));
        result.whenComplete((response, error) -> clearActive(cancellation));
        return result;
    }

    private boolean capturesModelExchange() {
        for (AgentPlugin plugin : agent.getPlugins()) {
            if (plugin.capturesModelExchange()) return true;
        }
        return false;
    }

    private CompletableFuture<ModelResponse> proceedModel(
            ModelInvocation invocation,
            int index,
            CancellationGroup cancellations) {
        Objects.requireNonNull(invocation, "invocation");
        if (index >= agent.getModelInterceptors().size()) {
            return invokeModelTerminal(invocation, cancellations);
        }
        ModelInterceptor interceptor = agent.getModelInterceptors().get(index);
        try {
            CompletableFuture<ModelResponse> result = interceptor.intercept(
                invocation,
                next -> proceedModel(next, index + 1, cancellations)
            );
            if (result == null) {
                return Futures.failed(new IllegalStateException(
                    "ModelInterceptor returned null future: "
                        + interceptor.getClass().getName()
                ));
            }
            cancellations.add(() -> result.cancel(true));
            return result;
        } catch (Throwable error) {
            return Futures.failed(error);
        }
    }

    private CompletableFuture<ModelResponse> invokeModelTerminal(
            ModelInvocation invocation,
            CancellationGroup cancellations) {
        try {
            if (invocation.isStreaming()) {
                StreamingChatModel streaming = (StreamingChatModel) agent.getModel();
                ModelStream stream = streaming.generateStream(
                    invocation.getRequest(),
                    new ModelStreamListener() {
                        @Override
                        public void onEvent(
                                io.github.gitsilence.agent.model.stream.ModelStreamEvent event) {
                            emit(AgentEvent.modelStream(
                                nextSequence(), state.getRunId(),
                                agent.descriptor().getName(), state.getStep(), event
                            ));
                        }
                    }
                );
                if (stream == null) {
                    return Futures.failed(new IllegalStateException(
                        "Streaming model returned null stream"
                    ));
                }
                CompletableFuture<ModelResponse> completion = stream.completion();
                cancellations.add(stream::cancel);
                cancellations.add(() -> completion.cancel(true));
                return completion;
            }

            CompletableFuture<ModelResponse> future =
                agent.getModel().generate(invocation.getRequest());
            if (future == null) {
                return Futures.failed(new IllegalStateException("Model returned null future"));
            }
            cancellations.add(() -> future.cancel(true));
            return future;
        } catch (Throwable error) {
            return Futures.failed(error);
        }
    }

    private Optional<AgentResult> evaluateTermination() {
        for (TerminationCondition condition : agent.getTerminationConditions()) {
            Optional<StopSignal> signal = condition.evaluate(state.snapshot());
            if (signal.isPresent()) {
                StopSignal stop = signal.get();
                if (stop.isCompleted()) {
                    state.complete(stop.getOutput(), stop.getReason());
                } else {
                    state.stop(stop.getReason());
                }
                return Optional.of(new AgentResult(state.snapshot()));
            }
        }
        return Optional.empty();
    }

    private CompletableFuture<AgentResult> fail(Throwable error) {
        state.fail(error);
        emitFailed(error);
        return Futures.failed(new AgentExecutionException(
            "Agent '" + agent.descriptor().getName() + "' failed",
            error,
            state.snapshot()
        ));
    }

    private void activate(Runnable cancellation) {
        if (cancelled.get()) {
            cancellation.run();
            return;
        }
        activeCancellation.set(cancellation);
        if (cancelled.get() && activeCancellation.compareAndSet(cancellation, null)) {
            cancellation.run();
        }
    }

    private void clearActive(Runnable cancellation) {
        activeCancellation.compareAndSet(cancellation, null);
    }

    private void cancelActive() {
        Runnable cancellation = activeCancellation.getAndSet(null);
        if (cancellation != null) {
            cancellation.run();
        }
    }

    private long nextSequence() {
        return eventSequence.incrementAndGet();
    }

    private void emit(AgentEvent event) {
        for (AgentPlugin plugin : agent.getPlugins()) {
            try {
                plugin.onEvent(event);
            } catch (Throwable ignored) {
                // Plugin observers are isolated from execution control.
            }
        }
        try {
            listener.onEvent(event);
        } catch (Throwable ignored) {
            // Observers must not change Agent execution semantics.
        }
    }

    private void emitTerminal(AgentResult result) {
        if (!terminalEventEmitted.compareAndSet(false, true)) {
            return;
        }
        AgentEventType type = result.getStatus() == ExecutionStatus.COMPLETED
            ? AgentEventType.TURN_COMPLETED : AgentEventType.TURN_STOPPED;
        emit(AgentEvent.lifecycle(
            nextSequence(), type, state.getRunId(), agent.descriptor().getName(),
            state.getStep(), result.getState(), null
        ));
    }

    private void emitFailed(Throwable error) {
        if (!terminalEventEmitted.compareAndSet(false, true)) {
            return;
        }
        emit(AgentEvent.lifecycle(
            nextSequence(), AgentEventType.TURN_FAILED,
            state.getRunId(), agent.descriptor().getName(), state.getStep(),
            state.snapshot(), error
        ));
    }

    private void emitCancelled() {
        if (!terminalEventEmitted.compareAndSet(false, true)) {
            return;
        }
        emit(AgentEvent.lifecycle(
            nextSequence(), AgentEventType.TURN_CANCELLED,
            state.getRunId(), agent.descriptor().getName(), state.getStep(),
            state.snapshot(), null
        ));
    }

    private void emitStepCompleted() {
        emit(AgentEvent.lifecycle(
            nextSequence(), AgentEventType.STEP_COMPLETED,
            state.getRunId(), agent.descriptor().getName(), state.getStep(),
            null, null
        ));
    }

    private static final class CancellationGroup {
        private final List<Runnable> actions = new ArrayList<Runnable>();
        private boolean cancelled;

        private void add(Runnable action) {
            boolean runNow;
            synchronized (this) {
                runNow = cancelled;
                if (!runNow) {
                    actions.add(action);
                }
            }
            if (runNow) {
                cancelQuietly(action);
            }
        }

        private void cancel() {
            List<Runnable> pending;
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                pending = new ArrayList<Runnable>(actions);
                actions.clear();
            }
            for (Runnable action : pending) {
                cancelQuietly(action);
            }
        }

        private static void cancelQuietly(Runnable action) {
            try {
                action.run();
            } catch (Throwable ignored) {
                // Cancellation is best effort across plugin and provider futures.
            }
        }
    }
}
