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

public final class AgentRunner implements AutoCloseable {

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
        if (parentPath.containsAgent(agent.getInstanceId())) {
            return Futures.failed(new IllegalStateException(
                "Recursive agent delegation detected: " + parentPath
                    + " -> " + agent.descriptor().getName()
            ));
        }
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
        String runId = UUID.randomUUID().toString();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        if (!agent.getInstructions().trim().isEmpty()) {
            messages.add(ChatMessage.system(agent.getInstructions()));
        }
        messages.addAll(request.getInitialMessages());
        messages.add(ChatMessage.user(request.getInput()));

        Map<String, Object> metadata =
            new LinkedHashMap<String, Object>(request.getMetadata());
        metadata.put("runId", runId);
        metadata.put("turnId", runId);
        metadata.put("traceId", traceId(request, runId));
        metadata.put("agentName", agent.descriptor().getName());
        metadata.put("invocationPath", path.getAgentNames());

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
