package io.github.gitsilence.agent.runtime;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.model.ModelRequest;
import io.github.gitsilence.agent.model.ModelResponse;
import io.github.gitsilence.agent.state.AgentState;
import io.github.gitsilence.agent.tool.ToolContext;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;
import io.github.gitsilence.agent.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class AgentLoop {

    private final Agent agent;
    private final AgentState state;
    private final AgentRunner runner;
    private final InvocationPath path;
    private final ToolExecutor toolExecutor;

    AgentLoop(Agent agent,
              AgentState state,
              AgentRunner runner,
              InvocationPath path) {
        this.agent = agent;
        this.state = state;
        this.runner = runner;
        this.path = path;
        this.toolExecutor = new ToolExecutor(
            agent.getToolRegistry(),
            agent.getToolExecutionMode(),
            agent.getToolErrorPolicy(),
            agent.getToolTimeout()
        );
    }

    CompletableFuture<AgentResult> run() {
        state.start();
        final CompletableFuture<AgentResult> workflow;
        try {
            workflow = iterate();
        } catch (Throwable error) {
            return fail(error);
        }

        CompletableFuture<AgentResult> result = new CompletableFuture<AgentResult>();
        workflow.whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else {
                Throwable actual = Futures.unwrap(error);
                state.fail(actual);
                result.completeExceptionally(new AgentExecutionException(
                    "Agent '" + agent.descriptor().getName() + "' failed",
                    actual,
                    state.snapshot()
                ));
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                state.cancel();
                workflow.cancel(true);
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
        ModelRequest request = new ModelRequest(
            state.messagesSnapshot(),
            new ArrayList<>(agent.getToolRegistry().definitions()),
            agent.getModelOptions()
        );

        final CompletableFuture<ModelResponse> modelFuture;
        try {
            modelFuture = agent.getModel().generate(request);
            if (modelFuture == null) {
                return Futures.failed(new IllegalStateException("Model returned null future"));
            }
        } catch (Throwable error) {
            return Futures.failed(error);
        }

        return modelFuture.thenCompose(response -> {
            if (response == null) {
                return Futures.failed(new IllegalStateException("Model returned null response"));
            }
            ChatMessage assistant = response.getAssistantMessage();
            state.appendMessage(assistant);

            if (assistant.getToolCalls().isEmpty()) {
                if (assistant.getContent() == null) {
                    return Futures.failed(new IllegalStateException(
                        "Final assistant message has null content"
                    ));
                }
                state.complete(assistant.getContent());
                return CompletableFuture.completedFuture(
                    new AgentResult(state.snapshot())
                );
            }

            return toolExecutor.executeAll(
                assistant.getToolCalls(),
                call -> new ToolContext(call.getId(), state, runner, path)
            ).thenCompose(records -> {
                appendToolResults(records);
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
        return Futures.failed(new AgentExecutionException(
            "Agent '" + agent.descriptor().getName() + "' failed",
            error,
            state.snapshot()
        ));
    }
}
