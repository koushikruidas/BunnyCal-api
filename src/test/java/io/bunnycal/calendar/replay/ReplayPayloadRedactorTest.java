package io.bunnycal.calendar.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayPayloadRedactorTest {

    private final ReplayPayloadRedactor redactor = new ReplayPayloadRedactor();

    @Test
    void redactsSensitiveFields_recursively() {
        String raw = "{\"summary\":\"Meeting\",\"attendees\":[{\"email\":\"a@x.com\",\"displayName\":\"Alice\"}],\"nested\":{\"description\":\"secret\"}}";
        String out = redactor.redact(raw);

        assertTrue(out.contains("\"summary\":\"[REDACTED]\""));
        assertTrue(out.contains("\"email\":\"[REDACTED]\""));
        assertTrue(out.contains("\"displayName\":\"[REDACTED]\""));
        assertTrue(out.contains("\"description\":\"[REDACTED]\""));
    }

    @Test
    void malformedPayload_isReturnedAsIs_forDeterministicReplay() {
        String raw = "not-json";
        assertEquals(raw, redactor.redact(raw));
    }
}
