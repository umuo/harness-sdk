package io.github.gitsilence.agent;

import io.github.gitsilence.agent.model.ToolCall;
import io.github.gitsilence.agent.tool.ToolExecutionRecord;
import io.github.gitsilence.agent.tool.ToolExecutionTiming;
import io.github.gitsilence.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolExecutionTimingTest {

    @Test
    void legacyRecordConstructorTreatsExecutionStartAsDispatchTime() {
        Instant startedAt = Instant.parse("2026-08-31T00:00:00Z");
        Instant completedAt = startedAt.plusSeconds(2L);
        ToolExecutionRecord record = new ToolExecutionRecord(
            new ToolCall("call-1", "lookup", "{}"),
            ToolResult.success("done"),
            startedAt,
            completedAt
        );

        assertEquals(startedAt, record.getDispatchedAt());
        assertEquals(0L, record.getDispatchDurationNanos());
        assertEquals(Duration.ofSeconds(2L).toNanos(),
            record.getHandlerDurationNanos());
        assertEquals(record.getHandlerDurationNanos(),
            record.getTotalDurationNanos());
    }

    @Test
    void timingRejectsOutOfOrderWallClockValues() {
        Instant dispatchedAt = Instant.parse("2026-08-31T00:00:01Z");
        Instant earlier = dispatchedAt.minusMillis(1L);

        assertThrows(IllegalArgumentException.class, () ->
            new ToolExecutionTiming(dispatchedAt, earlier, dispatchedAt)
        );
    }
}
