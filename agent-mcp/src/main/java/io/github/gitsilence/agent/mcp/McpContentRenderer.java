package io.github.gitsilence.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

final class McpContentRenderer {

    private McpContentRenderer() {
    }

    static McpCallToolResult render(JsonNode result) {
        List<String> parts = new ArrayList<String>();
        boolean omitted = false;
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                RenderedBlock rendered = renderBlock(block);
                if (!rendered.text.isEmpty()) {
                    parts.add(rendered.text);
                }
                omitted = omitted || rendered.omitted;
            }
        }

        String structuredJson = "";
        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isNull()) {
            structuredJson = structured.toString();
            parts.add("[structured content]\n" + structuredJson);
        }
        if (parts.isEmpty()) {
            parts.add("(MCP tool returned no model-readable content)");
        }
        return new McpCallToolResult(
            result.path("isError").asBoolean(false),
            join(parts),
            result.toString(),
            structuredJson,
            omitted
        );
    }

    private static RenderedBlock renderBlock(JsonNode block) {
        String type = block.path("type").asText("");
        if ("text".equals(type)) {
            return new RenderedBlock(block.path("text").asText(""), false);
        }
        if ("resource_link".equals(type)) {
            return new RenderedBlock(resourceLink(block), false);
        }
        if ("resource".equals(type)) {
            return embeddedResource(block.path("resource"));
        }
        if ("image".equals(type) || "audio".equals(type)) {
            String mime = block.path("mimeType").asText("unknown");
            return new RenderedBlock(
                "[MCP " + type + " omitted from model context; mimeType="
                    + mime + "; exact result attached as a temporary file]",
                true
            );
        }
        return new RenderedBlock(
            "[unsupported MCP content type '" + safe(type)
                + "' omitted from model context; exact result attached as a temporary file]",
            true
        );
    }

    private static String resourceLink(JsonNode block) {
        StringBuilder text = new StringBuilder("[MCP resource link]");
        append(text, "name", block.path("name").asText(""));
        append(text, "title", block.path("title").asText(""));
        append(text, "uri", block.path("uri").asText(""));
        append(text, "mimeType", block.path("mimeType").asText(""));
        return text.toString();
    }

    private static RenderedBlock embeddedResource(JsonNode resource) {
        StringBuilder header = new StringBuilder("[MCP embedded resource]");
        append(header, "uri", resource.path("uri").asText(""));
        append(header, "mimeType", resource.path("mimeType").asText(""));
        JsonNode text = resource.get("text");
        if (text != null && text.isTextual()) {
            return new RenderedBlock(header.append('\n').append(text.asText()).toString(), false);
        }
        if (resource.has("blob")) {
            return new RenderedBlock(
                header.append("\n[binary resource omitted; exact result attached as a temporary file]")
                    .toString(),
                true
            );
        }
        return new RenderedBlock(header.toString(), false);
    }

    private static void append(StringBuilder target, String name, String value) {
        if (value != null && !value.isEmpty()) {
            target.append(' ').append(name).append('=').append(value);
        }
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    private static String join(List<String> parts) {
        StringBuilder output = new StringBuilder();
        for (String part : parts) {
            if (output.length() > 0) {
                output.append("\n\n");
            }
            output.append(part);
        }
        return output.toString();
    }

    private static final class RenderedBlock {
        private final String text;
        private final boolean omitted;

        private RenderedBlock(String text, boolean omitted) {
            this.text = text;
            this.omitted = omitted;
        }
    }
}
