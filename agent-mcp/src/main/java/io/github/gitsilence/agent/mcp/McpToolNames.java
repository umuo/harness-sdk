package io.github.gitsilence.agent.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

final class McpToolNames {

    private static final Pattern NAMESPACE =
        Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,31}");
    private static final Pattern LOCAL_NAME =
        Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");

    private McpToolNames() {
    }

    static String localName(String namespace, String remoteName) {
        validateNamespace(namespace);
        Objects.requireNonNull(remoteName, "remoteName");
        String direct = namespace + "__" + remoteName;
        if (LOCAL_NAME.matcher(direct).matches()) {
            return direct;
        }

        String sanitized = remoteName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (sanitized.isEmpty()
                || !Character.isLetter(sanitized.charAt(0))
                    && sanitized.charAt(0) != '_') {
            sanitized = "_" + sanitized;
        }
        String prefix = namespace + "__";
        String suffix = "_" + shortHash(remoteName);
        int available = 64 - prefix.length() - suffix.length();
        if (sanitized.length() > available) {
            sanitized = sanitized.substring(0, available);
        }
        return prefix + sanitized + suffix;
    }

    static void validateNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                "MCP namespace must match " + NAMESPACE.pattern()
                    + ": " + namespace
            );
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                int next = digest[i] & 0xff;
                if (next < 16) hex.append('0');
                hex.append(Integer.toHexString(next));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
