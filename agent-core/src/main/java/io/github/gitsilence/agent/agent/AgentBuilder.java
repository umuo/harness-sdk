package io.github.gitsilence.agent.agent;

import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.runtime.AgentRunner;
import io.github.gitsilence.agent.runtime.TerminationCondition;
import io.github.gitsilence.agent.skill.Skill;
import io.github.gitsilence.agent.tool.AnnotatedTools;
import io.github.gitsilence.agent.tool.DefaultToolRegistry;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolErrorPolicy;
import io.github.gitsilence.agent.tool.ToolExecutionMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentBuilder {

    private String name;
    private String description;
    private String instructions = "";
    private ChatModel model;
    private ModelOptions modelOptions = ModelOptions.empty();
    private final List<Tool> tools = new ArrayList<Tool>();
    private final List<Skill> skills = new ArrayList<Skill>();
    private final List<TerminationCondition> terminationConditions =
        new ArrayList<TerminationCondition>();
    private int maxSteps = 10;
    private ToolExecutionMode toolExecutionMode = ToolExecutionMode.SEQUENTIAL;
    private ToolErrorPolicy toolErrorPolicy = ToolErrorPolicy.REPORT_TO_MODEL;
    private Duration toolTimeout = Duration.ofSeconds(60);
    private AgentRunner runner = AgentRunner.shared();

    public AgentBuilder name(String name) {
        this.name = name;
        return this;
    }

    public AgentBuilder description(String description) {
        this.description = description;
        return this;
    }

    public AgentBuilder instructions(String instructions) {
        this.instructions = instructions == null ? "" : instructions;
        return this;
    }

    public AgentBuilder model(ChatModel model) {
        this.model = model;
        return this;
    }

    public AgentBuilder modelOptions(ModelOptions options) {
        this.modelOptions = Objects.requireNonNull(options, "options");
        return this;
    }

    public AgentBuilder tool(Tool tool) {
        tools.add(Objects.requireNonNull(tool, "tool"));
        return this;
    }

    public AgentBuilder tool(Agent agent) {
        return tool(Objects.requireNonNull(agent, "agent").asTool());
    }

    public AgentBuilder toolsFrom(Object annotatedObject) {
        tools.addAll(AnnotatedTools.from(annotatedObject));
        return this;
    }

    public AgentBuilder skill(Skill skill) {
        skills.add(Objects.requireNonNull(skill, "skill"));
        return this;
    }

    public AgentBuilder maxSteps(int maxSteps) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
        return this;
    }

    public AgentBuilder parallelToolCalls(boolean parallel) {
        this.toolExecutionMode = parallel
            ? ToolExecutionMode.PARALLEL
            : ToolExecutionMode.SEQUENTIAL;
        return this;
    }

    public AgentBuilder toolErrorPolicy(ToolErrorPolicy policy) {
        this.toolErrorPolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    public AgentBuilder toolTimeout(Duration timeout) {
        this.toolTimeout = Objects.requireNonNull(timeout, "timeout");
        return this;
    }

    public AgentBuilder terminationCondition(TerminationCondition condition) {
        terminationConditions.add(Objects.requireNonNull(condition, "condition"));
        return this;
    }

    public AgentBuilder runner(AgentRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
        return this;
    }

    public Agent build() {
        AgentDescriptor descriptor = new AgentDescriptor(name, description);
        Objects.requireNonNull(model, "model");

        StringBuilder composedInstructions = new StringBuilder(instructions);
        DefaultToolRegistry.Builder registry = DefaultToolRegistry.builder();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        for (Skill skill : skills) {
            if (!skill.getInstructions().trim().isEmpty()) {
                if (composedInstructions.length() > 0) {
                    composedInstructions.append("\n\n");
                }
                composedInstructions.append("## Skill: ")
                    .append(skill.getName())
                    .append('\n')
                    .append(skill.getInstructions());
            }
            registry.registerAll(skill.getTools());
        }

        return new Agent(
            descriptor,
            composedInstructions.toString(),
            model,
            modelOptions,
            registry.build(),
            maxSteps,
            toolExecutionMode,
            toolErrorPolicy,
            toolTimeout,
            terminationConditions,
            runner
        );
    }
}
