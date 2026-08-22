package io.github.gitsilence.agent.state;

public final class AgentError {

    private final String type;
    private final String message;

    public AgentError(String type, String message) {
        this.type = type;
        this.message = message;
    }

    public static AgentError from(Throwable error) {
        return new AgentError(error.getClass().getName(), error.getMessage());
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }
}
