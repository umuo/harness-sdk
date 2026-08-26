package io.github.gitsilence.agent.model.stream;

import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelRequest;

/**
 * 支持增量事件的模型接口。流结束后仍必须产出完整的 ModelResponse，使 AgentLoop
 * 无需为流式模型维护另一套状态机。
 */
public interface StreamingChatModel extends ChatModel {

    ModelStream generateStream(ModelRequest request, ModelStreamListener listener);
}
