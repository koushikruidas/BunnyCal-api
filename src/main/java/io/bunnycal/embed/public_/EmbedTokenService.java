package io.bunnycal.embed.public_;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies short-lived embed tokens.
 *
 * Token format (dot-separated, base64url-encoded parts):
 *   {experienceId}.{experienceVersion}.{formVersion}.{expiryEpoch}.{sig}
 *
 * sig = HMAC-SHA256( experienceId + "." + experienceVersion + "." + formVersion + "." + expiry )
 */
@Service
public class EmbedTokenService {

    private static final String ALGO = "HmacSHA256";
    private final byte[] secretBytes;
    private final long ttlSeconds;

    public EmbedTokenService(
            @Value("${app.embed.token-secret}") String secret,
            @Value("${app.embed.token-ttl-seconds:7200}") long ttlSeconds) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public String mint(UUID experienceId, long experienceVersion, long formVersion) {
        long expiry = Instant.now().getEpochSecond() + ttlSeconds;
        String payload = experienceId + "." + experienceVersion + "." + formVersion + "." + expiry;
        String sig = hmac(payload);
        return b64(payload) + "." + sig;
    }

    public EmbedTokenClaims verify(String token) {
        if (token == null || token.isBlank()) throw invalid();
        int lastDot = token.lastIndexOf('.');
        if (lastDot < 0) throw invalid();

        String encodedPayload = token.substring(0, lastDot);
        String providedSig = token.substring(lastDot + 1);

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }

        if (!constantTimeEquals(hmac(payload), providedSig)) throw invalid();

        String[] parts = payload.split("\\.", -1);
        if (parts.length != 4) throw invalid();

        try {
            UUID experienceId = UUID.fromString(parts[0]);
            long experienceVersion = Long.parseLong(parts[1]);
            long formVersion = Long.parseLong(parts[2]);
            long expiry = Long.parseLong(parts[3]);

            if (Instant.now().getEpochSecond() > expiry) throw invalid();

            return new EmbedTokenClaims(experienceId, experienceVersion, formVersion);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secretBytes, ALGO));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception ex) {
            throw new RuntimeException("HMAC computation failed", ex);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static CustomException invalid() {
        return new CustomException(ErrorCode.EMBED_TOKEN_INVALID);
    }
}
