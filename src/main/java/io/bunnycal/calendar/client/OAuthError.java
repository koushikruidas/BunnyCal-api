package io.bunnycal.calendar.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.calendar.domain.CalendarProviderType;
import java.util.Locale;
import java.util.Set;

/**
 * Typed OAuth / Calendar API error. Built by provider HTTP clients from the response
 * body when available, and consumed by TokenRefresher / scheduler to drive REVOKED vs
 * ERROR vs FAILED transitions without relying on substring matches.
 */
public record OAuthError(
        String code,
        String description,
        int httpStatus,
        CalendarProviderType provider,
        OAuthErrorCategory category
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // OAuth 2.0 RFC 6749 terminal error codes that mean "user must re-consent".
    private static final Set<String> TERMINAL_CODES = Set.of(
            "invalid_grant",
            "invalid_token",
            "access_denied",
            "unauthorized_client",
            "invalid_client",
            "consent_required",
            "interaction_required",
            "login_required"
    );

    public static OAuthError fromHttp(int httpStatus, String body, CalendarProviderType provider) {
        String code = null;
        String description = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(body);
                JsonNode errorNode = root.get("error");
                if (errorNode != null) {
                    if (errorNode.isTextual()) {
                        // OAuth-style: {"error":"invalid_grant","error_description":"..."}
                        code = errorNode.asText(null);
                        JsonNode descNode = root.get("error_description");
                        if (descNode != null && !descNode.isNull()) {
                            description = descNode.asText(null);
                        }
                    } else if (errorNode.isObject()) {
                        // Microsoft Graph-style: {"error":{"code":"InvalidAuthenticationToken","message":"..."}}
                        JsonNode codeNode = errorNode.get("code");
                        JsonNode msgNode = errorNode.get("message");
                        if (codeNode != null && !codeNode.isNull()) {
                            code = codeNode.asText(null);
                        }
                        if (msgNode != null && !msgNode.isNull()) {
                            description = msgNode.asText(null);
                        }
                    }
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException parseEx) {
                // Body wasn't JSON; leave code/description null and fall through to status-based mapping.
            }
        }
        OAuthErrorCategory category = categorize(httpStatus, code);
        return new OAuthError(code, description, httpStatus, provider, category);
    }

    public static OAuthError network(CalendarProviderType provider, String message) {
        return new OAuthError(null, message, 0, provider, OAuthErrorCategory.TRANSIENT);
    }

    private static OAuthErrorCategory categorize(int httpStatus, String code) {
        if (code != null && !code.isBlank()) {
            String normalized = code.toLowerCase(Locale.ROOT);
            if (TERMINAL_CODES.contains(normalized)) {
                return OAuthErrorCategory.TERMINAL;
            }
            // Microsoft Graph variants (PascalCase) that indicate a dead grant.
            if (normalized.equals("invalidauthenticationtoken")
                    || normalized.equals("compactToken_unauthorized")
                    || normalized.equals("authenticationfailure")) {
                return OAuthErrorCategory.TERMINAL;
            }
        }
        if (httpStatus == 401 || httpStatus == 403) {
            // 401/403 without a recognized OAuth code: treat as terminal — the access
            // token is rejected and our refresh path will re-issue. If refresh itself
            // hits this, the grant is dead.
            return OAuthErrorCategory.TERMINAL;
        }
        if (httpStatus == 429 || httpStatus >= 500) {
            return OAuthErrorCategory.TRANSIENT;
        }
        if (httpStatus == 400 && code != null) {
            // 400 with an OAuth error code we didn't recognize: leave as UNKNOWN so
            // we observe instead of silently classifying as terminal.
            return OAuthErrorCategory.UNKNOWN;
        }
        return OAuthErrorCategory.UNKNOWN;
    }

    /**
     * Stable symbolic identifier suitable for last_error_code and metric tags.
     * Always non-null.
     */
    public String stableCode() {
        if (code != null && !code.isBlank()) {
            return code.toLowerCase(Locale.ROOT);
        }
        if (httpStatus > 0) {
            return "http_" + httpStatus;
        }
        return "unknown";
    }
}
