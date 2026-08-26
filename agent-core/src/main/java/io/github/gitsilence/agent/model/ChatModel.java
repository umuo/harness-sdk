package io.github.gitsilence.agent.model;

import java.util.concurrent.CompletableFuture;

/**
 * 与模型提供商无关的完整响应接口。
 *
 * <p>Provider 负责把标准消息和 Tool 定义映射为线上协议，但永远不负责执行 Tool。</p>
 */
public interface ChatModel {

    CompletableFuture<ModelResponse> generate(ModelRequest request);
}
