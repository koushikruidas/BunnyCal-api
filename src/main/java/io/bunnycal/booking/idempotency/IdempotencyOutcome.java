package io.bunnycal.booking.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

public sealed interface IdempotencyOutcome permits IdempotencyOutcome.Fresh, IdempotencyOutcome.Replayed {

    ResponseEntity<?> toResponseEntity(ObjectMapper mapper);

    record Fresh<T>(int status, T body) implements IdempotencyOutcome {
        @Override
        public ResponseEntity<?> toResponseEntity(ObjectMapper mapper) {
            return ResponseEntity.status(status).body(body);
        }
    }

    record Replayed(int status, String bodyJson) implements IdempotencyOutcome {
        @Override
        public ResponseEntity<?> toResponseEntity(ObjectMapper mapper) {
            Object body = bodyJson;
            try {
                JsonNode parsed = mapper.readTree(bodyJson);
                body = parsed;
            } catch (Exception ignored) {
                // Fallback to raw cached body if it is not valid JSON.
            }
            return ResponseEntity.status(status)
                    .header("Idempotency-Replayed", "true")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(body);
        }
    }

    static String oversizedReplayBody() {
        return "{\"success\":true,\"data\":{\"replayHint\":\"IDEMPOTENCY_RESPONSE_OMITTED\"},\"error\":null}";
    }
}
