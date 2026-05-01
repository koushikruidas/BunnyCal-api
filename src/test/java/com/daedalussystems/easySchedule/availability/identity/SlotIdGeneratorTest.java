package com.daedalussystems.easySchedule.availability.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlotIdGeneratorTest {

    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant START = Instant.parse("2026-05-01T13:00:00Z");
    private static final Instant END = Instant.parse("2026-05-01T13:30:00Z");

    @Test
    void deterministic_sameInputs_producesSameId() {
        String a = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 7L);
        String b = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 7L);
        assertEquals(a, b);
    }

    @Test
    void differentVersion_producesDifferentId() {
        String v7 = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 7L);
        String v8 = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 8L);
        assertNotEquals(v7, v8);
    }

    @Test
    void differentHost_producesDifferentId() {
        UUID otherHost = UUID.fromString("00000000-0000-0000-0000-000000000099");
        String a = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 7L);
        String b = SlotIdGenerator.generate(otherHost, EVENT_TYPE, START, END, 7L);
        assertNotEquals(a, b);
    }

    @Test
    void differentEventType_producesDifferentId() {
        UUID otherEventType = UUID.fromString("00000000-0000-0000-0000-000000000099");
        String a = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 7L);
        String b = SlotIdGenerator.generate(HOST, otherEventType, START, END, 7L);
        assertNotEquals(a, b);
    }

    @Test
    void differentStart_producesDifferentId() {
        Instant otherStart = START.plusSeconds(60);
        String a = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 7L);
        String b = SlotIdGenerator.generate(HOST, EVENT_TYPE, otherStart, END, 7L);
        assertNotEquals(a, b);
    }

    @Test
    void differentEnd_producesDifferentId() {
        Instant otherEnd = END.plusSeconds(60);
        String a = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 7L);
        String b = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, otherEnd, 7L);
        assertNotEquals(a, b);
    }

    @Test
    void output_isUrlSafe_withV1Prefix() {
        String slotId = SlotIdGenerator.generate(HOST, EVENT_TYPE, START, END, 7L);
        assertTrue(slotId.startsWith("v1:"));
        String body = slotId.substring("v1:".length());
        assertFalse(body.contains("+"));
        assertFalse(body.contains("/"));
        assertFalse(body.contains("="));
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> SlotIdGenerator.generate(null, EVENT_TYPE, START, END, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> SlotIdGenerator.generate(HOST, null, START, END, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> SlotIdGenerator.generate(HOST, EVENT_TYPE, null, END, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> SlotIdGenerator.generate(HOST, EVENT_TYPE, START, null, 1L));
    }
}
