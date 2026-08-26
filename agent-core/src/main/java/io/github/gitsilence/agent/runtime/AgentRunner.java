package io.github.gitsilence.agent.runtime;

import io.github.gitsilence.agent.agent.Agent;
import io.github.gitsilence.agent.agent.AgentRequest;
import io.github.gitsilence.agent.agent.AgentResult;
import io.github.gitsilence.agent.model.ChatMessage;
import io.github.gitsilence.agent.state.AgentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 的执行资源所有者和每次 Turn 的状态工厂。
 *
 * <p>Runner 持有工作线程池、超时调度器和子 Agent 深度限制；真正的固定循环由
 * {@link AgentLoop} 完成。默认共享实例使用守护线程，不需要由业务代码关闭。</p>
 */
public final class AgentRunner implements AutoCloseable {

    /** 全局共享 Runner 不拥有关闭权，避免某个 Agent 影响其他 Agent。 */
    private static final AgentRunner SHARED = new AgentRunner(
        Executors.newCachedThreadPool(new NamedDaemonThreadFactory("agent-worker")),
        Executors.newSingleThreadScheduledExecutor(
            new NamedDaemonThreadFactory("agent-scheduler")
        ),
        4,
        false,
        false
    );

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final int maxSubAgentDepth;
    private final boolean closeExecutor;
    private final boolean closeScheduler;

    private AgentRunner(ExecutorService executor,
                        ScheduledExecutorService scheduler,
                        int maxSubAgentDepth,
                        boolean closeExecutor,
                        boolean closeScheduler) {
        this.executor = executor;
        this.scheduler = scheduler;
        this.maxSubAgentDepth = maxSubAgentDepth;
        this.closeExecutor = closeExecutor;
        this.closeScheduler = closeScheduler;
    }

    public static AgentRunner shared() {
        return SHARED;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletableFuture<AgentResult> runAsync(Agent agent, AgentRequest request) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(request, "request");
        InvocationPath path = InvocationPath.root(
            agent.getInstanceId(), agent.descriptor().getName()
        );
        return runInternal(agent, request, path, AgentEventListener.noop(), false);
    }

    public CompletableFuture<AgentResult> runStreamingAsync(
            Agent agent,
            AgentRequest request,
            AgentEventListener listener) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        InvocationPath path = InvocationPath.root(
            agent.getInstanceId(), agent.descriptor().getName()
        );
        return runInternal(agent, request, path, listener, true);
    }

    public CompletableFuture<AgentResult> runChildAsync(Agent agent,
                                                        AgentRequest request,
                                                        InvocationPath parentPath) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(parentPath, "parentPath");
        // 同一个 Agent 实例不能在自己的调用路径中再次出现，防止 A -> B -> A。
        if (parentPath.containsAgent(agent.getInstanceId())) {
            return Futures.failed(new IllegalStateException(
                "Recursive agent delegation detected: " + parentPath
                    + " -> " + agent.descriptor().getName()
            ));
        }
        // 深度限制同时保护合法但失控的长委托链。
        if (parentPath.depth() >= maxSubAgentDepth) {
            return Futures.failed(new IllegalStateException(
                "Maximum sub-agent depth exceeded: " + maxSubAgentDepth
            ));
        }
        return runInternal(
            agent,
            request,
            parentPath.append(agent.getInstanceId(), agent.descriptor().getName()),
            AgentEventListener.noop(),
            false
        );
    }

    private CompletableFuture<AgentResult> runInternal(Agent agent,
                                                       AgentRequest request,
                                                       InvocationPath path,
                                                       AgentEventListener listener,
                                                       boolean streamModel) {
        // 每次根调用和子调用都有独立 turnId；当前版本 runId 是它的兼容别名。
        String runId = UUID.randomUUID().toString();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        if (!agent.getInstructions().trim().isEmpty()) {
            messages.add(ChatMessage.system(agent.getInstructions()));
        }
        messages.addAll(request.getInitialMessages());
        messages.add(ChatMessage.user(request.getInput()));

        // 统一写入 SDK 运行字段；traceId 允许调用方预先提供，用于接入外部追踪。
        Map<String, Object> metadata =
            new LinkedHashMap<String, Object>(request.getMetadata());
        metadata.put("runId", runId);
        metadata.put("turnId", runId);
        metadata.put("traceId", traceId(request, runId));
        metadata.put("agentName", agent.descriptor().getName());
        metadata.put("invocationPath", path.getAgentNames());

        // 请求只负责播种初始数据；后续所有变化都限定在这个新状态中。
        AgentState state = new AgentState(
            runId,
            agent.descriptor().getName(),
            messages,
            metadata,
            request.getVariables()
        );
        return new AgentLoop(
            agent, state, this, path, listener, streamModel
        ).run();
    }

    private static String traceId(AgentRequest request, String fallback) {
        Object configured = request.getMetadata().get("traceId");
        if (configured instanceof String
                && !((String) configured).trim().isEmpty()) {
            return (String) configured;
        }
        return fallback;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    @Override
    public void close() {
        // 只关闭 Builder 自动创建、由当前 Runner 拥有的线程池。
        if (closeExecutor) {
            executor.shutdownNow();
        }
        if (closeScheduler) {
            scheduler.shutdownNow();
        }
    }

    public static final class Builder {
        private ExecutorService executor;
        private ScheduledExecutorService scheduler;
        private int maxSubAgentDepth = 4;

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder maxSubAgentDepth(int maxSubAgentDepth) {
            if (maxSubAgentDepth < 1) {
                throw new IllegalArgumentException("maxSubAgentDepth must be positive");
            }
            this.maxSubAgentDepth = maxSubAgentDepth;
            return this;
        }

        public AgentRunner build() {
            // 调用方注入线程池时，其生命周期仍由调用方负责。
            boolean ownsExecutor = executor == null;
            boolean ownsScheduler = scheduler == null;
            ExecutorService actualExecutor = executor == null
                ? Executors.newCachedThreadPool(
                    new NamedDaemonThreadFactory("agent-worker")
                )
                : executor;
            ScheduledExecutorService actualScheduler = scheduler == null
                ? Executors.newSingleThreadScheduledExecutor(
                    new NamedDaemonThreadFactory("agent-scheduler")
                )
                : scheduler;
            return new AgentRunner(
                actualExecutor,
                actualScheduler,
                maxSubAgentDepth,
                ownsExecutor,
                ownsScheduler
            );
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                runnable,
                prefix + "-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}
