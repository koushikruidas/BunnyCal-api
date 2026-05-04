package com.daedalussystems.easySchedule.calendar.auth;

import com.daedalussystems.easySchedule.calendar.config.CalendarSecurityProperties;
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

    private final CalendarSecurityProperties properties;

    public OAuthStateService(CalendarSecurityProperties properties) {
        this.properties = properties;
    }

    public String generate(UUID userId) {
        long expiresAt = Instant.now().plusSeconds(TTL_SECONDS).getEpochSecond();
        String payload = userId + ":" + expiresAt;
        String signature = sign(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    public UUID validateAndExtractUserId(String state) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid state format");
            }
            String payload = parts[0] + ":" + parts[1];
            if (!sign(payload).equals(parts[2])) {
                throw new IllegalArgumentException("Invalid state signature");
            }
            long expiresAt = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() > expiresAt) {
                throw new IllegalArgumentException("State expired");
            }
            return UUID.fromString(parts[0]);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid state", ex);
        }
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
