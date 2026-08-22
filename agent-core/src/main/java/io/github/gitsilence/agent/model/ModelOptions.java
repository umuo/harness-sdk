package io.github.gitsilence.agent.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModelOptions {

    private static final ModelOptions EMPTY = builder().build();

    private final Double temperature;
    private final Integer maxTokens;
    private final Map<String, Object> extensions;

    private ModelOptions(Builder builder) {
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.extensions = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(builder.extensions)
        );
    }

    public static ModelOptions empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Double getTemperature() {
        return temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }

    public static final class Builder {
        private Double temperature;
        private Integer maxTokens;
        private final Map<String, Object> extensions = new LinkedHashMap<String, Object>();

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive");
            }
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder extension(String name, Object value) {
            extensions.put(name, value);
            return this;
        }

        public ModelOptions build() {
            return new ModelOptions(this);
        }
    }
}
