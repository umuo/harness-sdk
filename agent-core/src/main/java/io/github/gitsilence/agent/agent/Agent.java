package io.github.gitsilence.agent.agent;

import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.plugin.AgentPlugin;
import io.github.gitsilence.agent.plugin.ModelInterceptor;
import io.github.gitsilence.agent.plugin.ToolInterceptor;
import io.github.gitsilence.agent.runtime.AgentRunner;
import io.github.gitsilence.agent.runtime.AgentEventListener;
import io.github.gitsilence.agent.runtime.Futures;
import io.github.gitsilence.agent.runtime.TerminationCondition;
import io.github.gitsilence.agent.skill.SkillRegistry;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolErrorPolicy;
import io.github.gitsilence.agent.tool.ToolExecutionMode;
import io.github.gitsilence.agent.tool.ToolRegistry;
import io.github.gitsilence.agent.tool.ToolResultPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class Agent {

    private final String instanceId = UUID.randomUUID().toString();
    private final AgentDescriptor descriptor;
    private final String instructions;
    private final ChatModel model;
    private final ModelOptions modelOptions;
    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final int maxSteps;
    private final ToolExecutionMode toolExecutionMode;
    private final ToolErrorPolicy toolErrorPolicy;
    private final Duration toolTimeout;
    private final ToolResultPolicy toolResultPolicy;
    private final List<TerminationCondition> terminationConditions;
    private final List<AgentPlugin> plugins;
    private final List<ModelInterceptor> modelInterceptors;
    private final List<ToolInterceptor> toolInterceptors;
    private final AgentRunner runner;

    Agent(AgentDescriptor descriptor,
          String instructions,
          ChatModel model,
          ModelOptions modelOptions,
          ToolRegistry toolRegistry,
          SkillRegistry skillRegistry,
          int maxSteps,
          ToolExecutionMode toolExecutionMode,
          ToolErrorPolicy toolErrorPolicy,
          Duration toolTimeout,
          ToolResultPolicy toolResultPolicy,
          List<TerminationCondition> terminationConditions,
          List<AgentPlugin> plugins,
          List<ModelInterceptor> modelInterceptors,
          List<ToolInterceptor> toolInterceptors,
          AgentRunner runner) {
        this.descriptor = descriptor;
        this.instructions = instructions;
        this.model = model;
        this.modelOptions = modelOptions;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.maxSteps = maxSteps;
        this.toolExecutionMode = toolExecutionMode;
        this.toolErrorPolicy = toolErrorPolicy;
        this.toolTimeout = toolTimeout;
        this.toolResultPolicy = toolResultPolicy;
        this.terminationConditions = Collections.unmodifiableList(
            new ArrayList<TerminationCondition>(terminationConditions)
        );
        this.plugins = immutableCopy(plugins);
        this.modelInterceptors = immutableCopy(modelInterceptors);
        this.toolInterceptors = immutableCopy(toolInterceptors);
        this.runner = runner;
    }

    public static AgentBuilder builder() {
        return new AgentBuilder();
    }

    public AgentResult run(String input) {
        return run(AgentRequest.of(input));
    }

    public AgentResult run(AgentRequest request) {
        return Futures.join(runAsync(request));
    }

    public CompletableFuture<AgentResult> runAsync(String input) {
        return runAsync(AgentRequest.of(input));
    }

    public CompletableFuture<AgentResult> runAsync(AgentRequest request) {
        return runner.runAsync(this, request);
    }

    /**
     * Runs this Agent with lifecycle events and model deltas when the configured
     * model implements StreamingChatModel. Non-streaming models still emit all
     * lifecycle, model-completed and tool events.
     */
    public CompletableFuture<AgentResult> runStreamingAsync(
            String input,
            AgentEventListener listener) {
        return runStreamingAsync(AgentRequest.of(input), listener);
    }

    public CompletableFuture<AgentResult> runStreamingAsync(
            AgentRequest request,
            AgentEventListener listener) {
        return runner.runStreamingAsync(this, request, listener);
    }

    public Tool asTool() {
        return new AgentTool(this, descriptor.getName(), descriptor.getDescription());
    }

    public Tool asTool(String name, String description) {
        return new AgentTool(this, name, description);
    }

    public String getInstanceId() { return instanceId; }
    public AgentDescriptor descriptor() { return descriptor; }
    public String getInstructions() { return instructions; }
    public ChatModel getModel() { return model; }
    public ModelOptions getModelOptions() { return modelOptions; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public SkillRegistry getSkillRegistry() { return skillRegistry; }
    public int getMaxSteps() { return maxSteps; }
    public ToolExecutionMode getToolExecutionMode() { return toolExecutionMode; }
    public ToolErrorPolicy getToolErrorPolicy() { return toolErrorPolicy; }
    public Duration getToolTimeout() { return toolTimeout; }
    public ToolResultPolicy getToolResultPolicy() { return toolResultPolicy; }
    public List<TerminationCondition> getTerminationConditions() {
        return terminationConditions;
    }
    public List<AgentPlugin> getPlugins() { return plugins; }
    public List<ModelInterceptor> getModelInterceptors() { return modelInterceptors; }
    public List<ToolInterceptor> getToolInterceptors() { return toolInterceptors; }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
