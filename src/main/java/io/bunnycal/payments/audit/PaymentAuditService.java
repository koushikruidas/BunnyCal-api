package io.bunnycal.payments.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes immutable financial audit records. Joins the caller's transaction (REQUIRED)
 * so audit rows commit or roll back atomically with the state change they describe.
 */
@Service
@RequiredArgsConstructor
public class PaymentAuditService {

    public static final String ACTOR_WEBHOOK = "WEBHOOK";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    private final PaymentAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String actor, String entityType, UUID entityId, String action,
                       Object before, Object after) {
        repository.save(PaymentAuditLog.builder()
                .actor(actor)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .build());
    }

    public static String userActor(UUID userId) {
        return "USER:" + userId;
    }

    public static String adminActor(UUID adminId) {
        return "ADMIN:" + adminId;
    }

    /**
     * Serializes any value to JSON for storage in a JSONB column. A bare String is
     * encoded as a JSON string literal (not stored raw), so callers can never write
     * invalid JSON into the audit table. To store a pre-built JSON document, pass a
     * {@link com.fasterxml.jackson.databind.JsonNode} or a Map/POJO — not a String.
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payment audit payload is not serializable", e);
        }
    }
}
