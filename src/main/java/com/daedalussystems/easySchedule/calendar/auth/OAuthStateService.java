package com.daedalussystems.easySchedule.calendar.auth;

import com.daedalussystems.easySchedule.calendar.config.CalendarSecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class OAuthStateService {
    private static final long TTL_SECONDS = 600;
    private static final String HMAC = "HmacSHA256";
    public static final String SOURCE_DASHBOARD = "dashboard";
    public static final String SOURCE_PUBLIC_BOOKING = "public-booking";

    private final CalendarSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public OAuthStateService(CalendarSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String generate(UUID userId) {
        return generate(userId, SOURCE_DASHBOARD, null, null);
    }

    public String generate(UUID userId, String source, String returnTo, String bookingSessionId) {
        try {
            long expiresAt = Instant.now().plusSeconds(TTL_SECONDS).getEpochSecond();
            OAuthStatePayload payload = new OAuthStatePayload(userId, source, returnTo, bookingSessionId, expiresAt);
            String payloadBase64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            String signature = sign(payloadBase64);
            return payloadBase64 + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate state", ex);
        }
    }

    public OAuthStatePayload validateAndExtract(String state) {
        try {
            String[] parts = state.split("\\.", 2);
            if (parts.length != 2) {
                throw new OAuthStateException(OAuthStateException.Reason.INVALID, "Invalid state format");
            }
            String payloadBase64 = parts[0];
            if (!sign(payloadBase64).equals(parts[1])) {
                throw new OAuthStateException(OAuthStateException.Reason.INVALID, "Invalid state signature");
            }
            OAuthStatePayload payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(payloadBase64),
                    OAuthStatePayload.class);
            if (payload.expiresAtEpochSeconds() <= 0) {
                throw new OAuthStateException(OAuthStateException.Reason.INVALID, "Invalid state expiration");
            }
            if (Instant.now().getEpochSecond() > payload.expiresAtEpochSeconds()) {
                throw new OAuthStateException(OAuthStateException.Reason.EXPIRED, "State expired");
            }
            if (payload.userId() == null) {
                throw new OAuthStateException(OAuthStateException.Reason.MISSING_USER, "State userId missing");
            }
            return payload;
        } catch (OAuthStateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new OAuthStateException(OAuthStateException.Reason.INVALID, "Invalid state", ex);
        } catch (Exception ex) {
            throw new OAuthStateException(OAuthStateException.Reason.INVALID, "Invalid state", ex);
        }
    }

    public UUID validateAndExtractUserId(String state) {
        return validateAndExtract(state).userId();
    }

    private String sign(String payload) {
        String secret = properties.getOauthStateSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("calendar.security.oauth-state-secret is required for OAuth state signing");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign state", ex);
        }
    }
}
