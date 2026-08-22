package io.github.gitsilence.agent.http;

public final class SseEvent {

    private final String event;
    private final String data;
    private final String id;

    public SseEvent(String event, String data, String id) {
        this.event = event == null || event.isEmpty() ? "message" : event;
        this.data = data == null ? "" : data;
        this.id = id;
    }

    public String getEvent() { return event; }
    public String getData() { return data; }
    public String getId() { return id; }
}
