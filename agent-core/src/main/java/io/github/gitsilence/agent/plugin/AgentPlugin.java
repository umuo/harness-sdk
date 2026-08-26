package io.github.gitsilence.agent.plugin;

import io.github.gitsilence.agent.runtime.AgentEvent;

import java.util.Collections;
import java.util.List;

/**
 * 在 Agent 构建阶段注册的一组运行时扩展点。
 *
 * <p>Plugin 本身不是动态模块系统；Agent 构建后，其监听器和拦截器顺序保持不变。</p>
 */
public interface AgentPlugin {

    default String name() {
        return getClass().getName();
    }

    /** 接收只读生命周期事实；异常会被隔离，不能用它控制执行。 */
    default void onEvent(AgentEvent event) {
    }

    default List<ModelInterceptor> modelInterceptors() {
        return Collections.emptyList();
    }

    default List<ToolInterceptor> toolInterceptors() {
        return Collections.emptyList();
    }

    /** 请求为模型调用捕获有界的 Provider 请求/响应正文。 */
    default boolean capturesModelExchange() {
        return false;
    }
}
