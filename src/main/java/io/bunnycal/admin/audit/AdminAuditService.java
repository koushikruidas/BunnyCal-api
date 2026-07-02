package io.bunnycal.admin.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes immutable admin audit records. Joins the caller's transaction (REQUIRED) so the
 * audit row commits or rolls back atomically with the action it describes — an action that
 * fails is never recorded as having happened.
 *
 * <p>Mirrors {@code PaymentAuditService}. Every state-changing admin endpoint should call
 * {@link #record} (or a module-specific wrapper) with before/after snapshots and a reason.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    /** Full record. IP/user-agent are read from the current request when available. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID adminId, String adminEmail, String action, String targetType,
                       UUID targetId, String reason, Object before, Object after) {
        HttpServletRequest request = currentRequest();
        repository.save(AdminAuditLog.builder()
                .adminId(adminId)
                .adminEmail(adminEmail)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .reason(reason)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .ipAddress(request == null ? null : clientIp(request))
                .userAgent(request == null ? null : truncate(request.getHeader("User-Agent"), 512))
                .build());
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For may be a comma-separated list; the first entry is the client.
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Serializes any value to JSON for a JSONB column. A bare String is encoded as a JSON
     * string literal, so callers can never write invalid JSON. Pass a Map/POJO/JsonNode to
     * store a structured document.
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Admin audit payload is not serializable", e);
        }
    }
}
