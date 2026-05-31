package io.bunnycal.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void hash_isSha256HexLength64() {
        String hash = RequestHasher.hash(Map.of("startTime", Instant.parse("2026-05-10T10:00:00Z")), mapper);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("^[0-9a-f]{64}$"));
    }

    @Test
    void hash_changesWhenGuestFieldsChange() {
        Map<String, Object> base = payload("guest@example.com", "Guest User");
        String first = RequestHasher.hash(base, mapper);
        String second = RequestHasher.hash(payload("other@example.com", "Guest User"), mapper);
        String third = RequestHasher.hash(payload("guest@example.com", "Another Name"), mapper);
        assertNotEquals(first, second);
        assertNotEquals(first, third);
    }

    @Test
    void hash_sameForNormalizedEquivalentPayload() {
        Map<String, Object> one = payload("guest@example.com", "Guest User");
        Map<String, Object> two = payload("guest@example.com", "Guest User");
        assertEquals(RequestHasher.hash(one, mapper), RequestHasher.hash(two, mapper));
    }

    @Test
    void hash_handlesNullGuestFields() {
        String hash = RequestHasher.hash(payload(null, null), mapper);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("^[0-9a-f]{64}$"));
    }

    private static Map<String, Object> payload(String guestEmail, String guestName) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("username", "host");
        map.put("eventTypeSlug", "intro");
        map.put("startTime", Instant.parse("2026-05-10T10:00:00Z"));
        map.put("guestEmail", guestEmail);
        map.put("guestName", guestName);
        return map;
    }
}
