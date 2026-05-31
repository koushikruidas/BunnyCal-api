package io.bunnycal.calendar.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ReplayPayloadRedactor {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "email", "displayName", "summary", "description", "location", "hangoutLink");

    private final ObjectMapper mapper = new ObjectMapper();

    public String redact(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return rawPayload;
        }
        try {
            Map<String, Object> parsed = mapper.readValue(rawPayload, new TypeReference<>() {});
            Map<String, Object> redacted = redactMap(parsed);
            return mapper.writeValueAsString(redacted);
        } catch (Exception ex) {
            // Keep replay deterministic even for malformed payloads.
            return rawPayload;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactMap(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (SENSITIVE_KEYS.contains(key)) {
                out.put(key, "[REDACTED]");
                continue;
            }
            if (value instanceof Map<?, ?> m) {
                out.put(key, redactMap((Map<String, Object>) m));
                continue;
            }
            if (value instanceof Iterable<?> it) {
                java.util.List<Object> arr = new java.util.ArrayList<>();
                for (Object obj : it) {
                    if (obj instanceof Map<?, ?> sub) {
                        arr.add(redactMap((Map<String, Object>) sub));
                    } else {
                        arr.add(obj);
                    }
                }
                out.put(key, arr);
                continue;
            }
            out.put(key, value);
        }
        return out;
    }
}
