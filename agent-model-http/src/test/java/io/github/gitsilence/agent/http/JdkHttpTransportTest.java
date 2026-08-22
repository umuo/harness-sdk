package io.github.gitsilence.agent.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkHttpTransportTest {

    @Test
    void parsesNamedMultilineSseEventsAndTrailingEvent() throws Exception {
        String source = ": heartbeat\n"
            + "event: response.output_text.delta\n"
            + "id: evt-1\n"
            + "data: {\"first\":true,\n"
            + "data: \"second\":true}\n"
            + "\n"
            + "data: [DONE]\n";
        List<SseEvent> events = new ArrayList<SseEvent>();

        JdkHttpTransport.parseSse(
            new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
            events::add
        );

        assertEquals(2, events.size());
        assertEquals("response.output_text.delta", events.get(0).getEvent());
        assertEquals("evt-1", events.get(0).getId());
        assertEquals("{\"first\":true,\n\"second\":true}", events.get(0).getData());
        assertEquals("message", events.get(1).getEvent());
        assertEquals("[DONE]", events.get(1).getData());
    }
}
