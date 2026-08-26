package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.model.ModelResponse;

import java.util.concurrent.CompletableFuture;

/**
 * 模型调用拦截器。调用 {@code chain.proceed} 可继续责任链，也可以改写调用或直接
 * 返回结果实现短路；与只读的 Plugin 事件监听器不同，它会影响执行语义。
 */
@FunctionalInterface
public interface ModelInterceptor {

    CompletableFuture<ModelResponse> intercept(
        ModelInvocation invocation,
        ModelChain chain
    );
}
