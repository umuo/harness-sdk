package io.github.gitsilence.agent.model.stream;

import io.github.gitsilence.agent.model.ChatModel;
import io.github.gitsilence.agent.model.ModelRequest;

public interface StreamingChatModel extends ChatModel {

    ModelStream generateStream(ModelRequest request, ModelStreamListener listener);
}
