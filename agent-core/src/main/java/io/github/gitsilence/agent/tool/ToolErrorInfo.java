package io.github.gitsilence.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Structured, model-facing information about a failed tool call. */
public final class ToolErrorInfo {

    private static final Pattern CODE_PATTERN =
        Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final String code;
    private final String message;
    private final boolean retryable;
    private final String recoveryHint;
    private final Map<String, Object> details;

    private ToolErrorInfo(Builder builder) {
        this.code = validateCode(builder.code);
        this.message = requireText(builder.message, "message");
        this.retryable = builder.retryable;
        this.recoveryHint = normalizeOptional(builder.recoveryHint);
        this.details = Collections.unmodifiableMap(
            new LinkedHashMap<String, Object>(builder.details)
        );
    }

    public static Builder builder(String code, String message) {
        return new Builder(code, message);
    }

    public String toModelMessage() {
        StringBuilder output = new StringBuilder()
            .append("Error [")
            .append(code)
            .append("]: ")
            .append(message);
        if (recoveryHint != null) {
            output.append("\nRecovery: ").append(recoveryHint);
        }
        if (!details.isEmpty()) {
            output.append("\nDetails:");
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                output.append("\n- ")
                    .append(entry.getKey())
                    .append(": ")
                    .append(String.valueOf(entry.getValue()));
            }
        }
        return output.toString();
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public boolean isRetryable() { return retryable; }
    public String getRecoveryHint() { return recoveryHint; }
    public Map<String, Object> getDetails() { return details; }

    private static String validateCode(String code) {
        requireText(code, "code");
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException(
                "Tool error code must match " + CODE_PATTERN.pattern() + ": " + code
            );
        }
        return code;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    public static final class Builder {
        private final String code;
        private final String message;
        private boolean retryable;
        private String recoveryHint;
        private final Map<String, Object> details =
            new LinkedHashMap<String, Object>();

        private Builder(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public Builder retryable(boolean retryable) {
            this.retryable = retryable;
            return this;
        }

        public Builder recoveryHint(String recoveryHint) {
            this.recoveryHint = recoveryHint;
            return this;
        }

        public Builder detail(String name, Object value) {
            details.put(requireText(name, "detail name"), value);
            return this;
        }

        public ToolErrorInfo build() {
            return new ToolErrorInfo(this);
        }
    }
}
