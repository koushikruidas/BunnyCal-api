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

/**
 * Issues and verifies slot tokens for COLLECTIVE event types.
 *
 * <p>The token encodes a SHA-256 hash of the sorted participant roster so that any roster
 * change (add or remove) invalidates previously issued tokens. This prevents a booking
 * from proceeding against stale availability when the participant list has changed since
 * the slot was generated.
 *
 * <p>Token format: {@code cv1|ownerUserId|eventTypeId|startMs|endMs|issuedAtMs|rosterHash}
 * where {@code rosterHash} is the Base64-URL-encoded SHA-256 of the sorted participant UUIDs.
 *
 * <p>Uses an independent HMAC secret ({@code booking.public.collective-slot-token-secret})
 * to prevent cross-type token forgery between ROUND_ROBIN and COLLECTIVE paths.
 */
@Service
public class CollectiveSlotTokenService {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String VERSION = "cv1";
    private static final int EXPECTED_FIELDS = 7;

    private final byte[] secretBytes;

    public CollectiveSlotTokenService(
            @Value("${booking.public.collective-slot-token-secret:${JWT_SECRET:dev-collective-slot-secret}}")
            String secret) {
        this.secretBytes = (secret == null ? "dev-collective-slot-secret" : secret)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Issues a signed token for a collective slot.
     *
     * @param ownerUserId  the event type owner
     * @param eventTypeId  the event type
     * @param start        slot start (UTC)
     * @param end          slot end (UTC)
     * @param participantIds current participant roster (order-independent; sorted internally for hashing)
     */
    public String issue(UUID ownerUserId,
                        UUID eventTypeId,
                        Instant start,
                        Instant end,
                        List<UUID> participantIds) {
        String rosterHash = rosterHash(participantIds);
        String payload = String.join("|",
                VERSION,
                ownerUserId.toString(),
                eventTypeId.toString(),
                Long.toString(start.toEpochMilli()),
                Long.toString(end.toEpochMilli()),
                Long.toString(Instant.now().toEpochMilli()),
                rosterHash);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String encodedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
        return encodedPayload + "." + encodedSig;
    }

    /**
     * Verifies a collective slot token.
     *
     * @throws CustomException VALIDATION_ERROR if the token is missing, tampered, or malformed
     */
    public DecodedCollectiveToken verify(String token) {
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
        if (fields.length != EXPECTED_FIELDS || !VERSION.equals(fields[0])) {
            throw invalidToken();
        }
        try {
            return new DecodedCollectiveToken(
                    UUID.fromString(fields[1]),
                    UUID.fromString(fields[2]),
                    Instant.ofEpochMilli(Long.parseLong(fields[3])),
                    Instant.ofEpochMilli(Long.parseLong(fields[4])),
                    Instant.ofEpochMilli(Long.parseLong(fields[5])),
                    fields[6]);
        } catch (RuntimeException ex) {
            throw invalidToken();
        }
    }

    /**
     * Validates that the roster hash in {@code decoded} matches the current participant list.
     * Call this at hold time to reject tokens issued before a roster change.
     *
     * @throws CustomException VALIDATION_ERROR if the roster has changed since the token was issued
     */
    public void validateRosterMatch(DecodedCollectiveToken decoded, List<UUID> currentParticipantIds) {
        String currentHash = rosterHash(currentParticipantIds);
        if (!decoded.rosterHash().equals(currentHash)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Slot token is no longer valid: participant roster has changed.");
        }
    }

    /**
     * Computes the roster hash from {@code participantIds}.
     * Sorts lexicographically by UUID string so order of addition doesn't affect the hash.
     */
    public String rosterHash(List<UUID> participantIds) {
        String sorted = participantIds.stream()
                .map(UUID::toString)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(sorted.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALG));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign collective slot token", ex);
        }
    }

    private static CustomException invalidToken() {
        return new CustomException(ErrorCode.VALIDATION_ERROR, "Invalid collective slot token.");
    }

    public record DecodedCollectiveToken(
            UUID ownerUserId,
            UUID eventTypeId,
            Instant start,
            Instant end,
            Instant issuedAt,
            String rosterHash) {
    }
}
