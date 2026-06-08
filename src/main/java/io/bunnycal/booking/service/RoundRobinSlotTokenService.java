package io.bunnycal.booking.service;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RoundRobinSlotTokenService {

    private static final String HMAC = "HmacSHA256";
    private static final String VERSION = "v1";

    private final byte[] secretBytes;

    public RoundRobinSlotTokenService(
            @Value("${booking.public.round-robin-slot-token-secret:${JWT_SECRET:dev-round-robin-slot-secret}}")
            String secret) {
        this.secretBytes = (secret == null ? "dev-round-robin-slot-secret" : secret)
                .getBytes(StandardCharsets.UTF_8);
    }

    public String issue(UUID ownerUserId,
                        UUID eventTypeId,
                        Instant start,
                        Instant end,
                        List<UUID> candidateParticipantIds) {
        String payload = String.join("|",
                VERSION,
                ownerUserId.toString(),
                eventTypeId.toString(),
                Long.toString(start.toEpochMilli()),
                Long.toString(end.toEpochMilli()),
                Long.toString(Instant.now().toEpochMilli()),
                candidateParticipantIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse(""));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String encodedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
        return encodedPayload + "." + encodedSig;
    }

    public DecodedSlotToken verify(String token) {
        if (token == null || token.isBlank() || !token.contains(".")) {
            throw invalidToken();
        }
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            throw invalidToken();
        }
        String payload;
        byte[] expectedSig;
        byte[] actualSig;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            expectedSig = hmac(payload);
            actualSig = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException ex) {
            throw invalidToken();
        }
        if (!MessageDigest.isEqual(expectedSig, actualSig)) {
            throw invalidToken();
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 7 || !VERSION.equals(fields[0])) {
            throw invalidToken();
        }
        try {
            List<UUID> candidates = new ArrayList<>();
            if (!fields[6].isBlank()) {
                for (String raw : fields[6].split(",")) {
                    candidates.add(UUID.fromString(raw));
                }
            }
            return new DecodedSlotToken(
                    UUID.fromString(fields[1]),
                    UUID.fromString(fields[2]),
                    Instant.ofEpochMilli(Long.parseLong(fields[3])),
                    Instant.ofEpochMilli(Long.parseLong(fields[4])),
                    Instant.ofEpochMilli(Long.parseLong(fields[5])),
                    List.copyOf(candidates));
        } catch (RuntimeException ex) {
            throw invalidToken();
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secretBytes, HMAC));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign RR slot token", ex);
        }
    }

    private static CustomException invalidToken() {
        return new CustomException(ErrorCode.VALIDATION_ERROR, "Invalid round robin slot token.");
    }

    public record DecodedSlotToken(
            UUID ownerUserId,
            UUID eventTypeId,
            Instant start,
            Instant end,
            Instant issuedAt,
            List<UUID> candidateParticipantIds) {
    }
}
