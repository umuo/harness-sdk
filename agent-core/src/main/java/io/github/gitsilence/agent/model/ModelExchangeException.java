package io.github.gitsilence.agent.model;

import java.util.Objects;

/** A Model failure that retains its safe, header-free provider exchange. */
public final class ModelExchangeException extends ModelException {

    private final ModelExchange exchange;

    public ModelExchangeException(String message, ModelExchange exchange) {
        super(message);
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    public ModelExchangeException(String message,
                                  Throwable cause,
                                  ModelExchange exchange) {
        super(message, cause);
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    public ModelExchange getExchange() {
        return exchange;
    }
}
