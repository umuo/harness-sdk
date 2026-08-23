package io.github.gitsilence.agent.http;

public final class SseEvent {

    private final String event;
    private final String data;
    private final String id;
    private final String raw;

    public SseEvent(String event, String data, String id) {
        this(event, data, id, "");
    }

    public SseEvent(String event, String data, String id, String raw) {
        this.event = event == null || event.isEmpty() ? "message" : event;
        this.data = data == null ? "" : data;
        this.id = id;
        this.raw = raw == null ? "" : raw;
    }

    public String getEvent() { return event; }
    public String getData() { return data; }
    public String getId() { return id; }
    /** SSE block normalized to LF line endings, when provided by the transport. */
    public String getRaw() { return raw; }
}
