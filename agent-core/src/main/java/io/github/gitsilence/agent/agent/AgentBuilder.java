package io.github.gitsilence.agent.agent;

import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelOptions;
import io.github.gitsilence.agent.plugin.AgentPlugin;
import io.github.gitsilence.agent.plugin.ModelInterceptor;
import io.github.gitsilence.agent.plugin.ToolInterceptor;
import io.github.gitsilence.agent.runtime.AgentRunner;
import io.github.gitsilence.agent.runtime.TerminationCondition;
import io.github.gitsilence.agent.skill.Skill;
import io.github.gitsilence.agent.skill.SkillLoadTool;
import io.github.gitsilence.agent.skill.SkillLoader;
import io.github.gitsilence.agent.skill.SkillPromptFormatter;
import io.github.gitsilence.agent.skill.SkillRegistry;
import io.github.gitsilence.agent.tool.AnnotatedTools;
import io.github.gitsilence.agent.tool.BoundedToolResultPolicy;
import io.github.gitsilence.agent.tool.DefaultToolRegistry;
import io.github.gitsilence.agent.tool.Tool;
import io.github.gitsilence.agent.tool.ToolErrorPolicy;
import io.github.gitsilence.agent.tool.ToolExecutionMode;
import io.github.gitsilence.agent.tool.ToolResultPolicy;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 组装不可变 {@link Agent} 的构建器。
 *
 * <p>这里负责把 Tool、Skill 和 Plugin 的声明转换为运行时需要的不可变注册表。
 * 构建完成后再修改原始集合不会影响 Agent。</p>
 */
public final class AgentBuilder {

    private String name;
    private String description;
    private String instructions = "";
    private ChatModel model;
    private ModelOptions modelOptions = ModelOptions.empty();
    private final List<Tool> tools = new ArrayList<Tool>();
    private final List<Skill> skills = new ArrayList<Skill>();
    private final List<AgentPlugin> plugins = new ArrayList<AgentPlugin>();
    private final List<TerminationCondition> terminationConditions =
        new ArrayList<TerminationCondition>();
    /** 一步表示“一次模型调用 + 该模型请求的一批工具调用”。 */
    private int maxSteps = 10;
    private ToolExecutionMode toolExecutionMode = ToolExecutionMode.SEQUENTIAL;
    private ToolErrorPolicy toolErrorPolicy = ToolErrorPolicy.REPORT_TO_MODEL;
    private Duration toolTimeout = Duration.ofSeconds(60);
    private ToolResultPolicy toolResultPolicy = BoundedToolResultPolicy.defaults();
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

    public AgentBuilder tools(Iterable<? extends Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        for (Tool tool : tools) {
            tool(tool);
        }
        return this;
    }

    public AgentBuilder tool(Agent agent) {
        // Agent 本身不是 Tool；通过适配器保证每次委托都创建独立的子状态。
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

    public AgentBuilder skill(Path skillDirectoryOrFile) {
        return skill(SkillLoader.load(skillDirectoryOrFile));
    }

    public AgentBuilder skills(Iterable<? extends Skill> skills) {
        Objects.requireNonNull(skills, "skills");
        for (Skill skill : skills) {
            skill(skill);
        }
        return this;
    }

    /**
     * 递归发现 Skill；任意 {@code SKILL.md} 无效时立即构建失败。
     * 如需接受部分有效结果，应直接调用 {@link SkillLoader#discover(Path)}。
     */
    public AgentBuilder skillsFrom(Path root) {
        SkillLoader.Discovery discovery = SkillLoader.discover(root);
        if (discovery.hasDiagnostics()) {
            StringBuilder message = new StringBuilder(
                "Cannot register Skills from " + root + ':'
            );
            for (SkillLoader.Diagnostic diagnostic : discovery.getDiagnostics()) {
                message.append("\n- ").append(diagnostic.getMessage());
            }
            throw new IllegalArgumentException(message.toString());
        }
        return skills(discovery.getSkills());
    }

    public AgentBuilder plugin(AgentPlugin plugin) {
        plugins.add(Objects.requireNonNull(plugin, "plugin"));
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
        // 该配置只影响同一模型响应中多个 Tool Call 的执行方式；回填顺序仍固定。
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

    public AgentBuilder toolResultPolicy(ToolResultPolicy policy) {
        this.toolResultPolicy = Objects.requireNonNull(policy, "policy");
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

        // 系统提示只放入 Skill 元数据，正文等模型调用 skill_load 时再按需加载。
        SkillRegistry skillRegistry = SkillRegistry.of(skills);
        StringBuilder composedInstructions = new StringBuilder(instructions);
        String skillPrompt = SkillPromptFormatter.format(skillRegistry);
        if (!skillPrompt.isEmpty()) {
            if (composedInstructions.length() > 0) {
                composedInstructions.append("\n\n");
            }
            composedInstructions.append(skillPrompt);
        }
        DefaultToolRegistry.Builder registry = DefaultToolRegistry.builder();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        if (!skillRegistry.isEmpty()) {
            // 注册 Skill 时自动暴露加载工具，无需调用方手工添加。
            registry.register(new SkillLoadTool(skillRegistry));
        }

        // 插件在构建阶段展开为有序拦截器链，执行期间不再动态增删。
        List<ModelInterceptor> modelInterceptors =
            new ArrayList<ModelInterceptor>();
        List<ToolInterceptor> toolInterceptors =
            new ArrayList<ToolInterceptor>();
        for (AgentPlugin plugin : plugins) {
            requirePluginName(plugin);
            addAllNonNull(
                modelInterceptors,
                plugin.modelInterceptors(),
                plugin.name() + ".modelInterceptors"
            );
            addAllNonNull(
                toolInterceptors,
                plugin.toolInterceptors(),
                plugin.name() + ".toolInterceptors"
            );
        }

        return new Agent(
            descriptor,
            composedInstructions.toString(),
            model,
            modelOptions,
            registry.build(),
            skillRegistry,
            maxSteps,
            toolExecutionMode,
            toolErrorPolicy,
            toolTimeout,
            toolResultPolicy,
            terminationConditions,
            plugins,
            modelInterceptors,
            toolInterceptors,
            runner
        );
    }

    private static void requirePluginName(AgentPlugin plugin) {
        String name = plugin.name();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("plugin name must not be blank");
        }
    }

    private static <T> void addAllNonNull(List<T> target,
                                          List<T> values,
                                          String source) {
        Objects.requireNonNull(values, source);
        for (T value : values) {
            target.add(Objects.requireNonNull(value, source + " entry"));
        }
    }
}
