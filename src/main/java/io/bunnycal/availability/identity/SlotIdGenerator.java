package io.bunnycal.availability.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class SlotIdGenerator {

    private static final String VERSION_PREFIX = "v1:";
    private static final String FIELD_SEPARATOR = "|";

    private SlotIdGenerator() {}

    public static String generate(UUID hostId, UUID eventTypeId, Instant start, Instant end, long version) {
        if (hostId == null || eventTypeId == null || start == null || end == null) {
            throw new IllegalArgumentException("hostId, eventTypeId, start, end are required");
        }

        String canonical = hostId
                + FIELD_SEPARATOR + eventTypeId
                + FIELD_SEPARATOR + start.toEpochMilli()
                + FIELD_SEPARATOR + end.toEpochMilli()
                + FIELD_SEPARATOR + version;

        byte[] digest = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return VERSION_PREFIX + encoded;
    }

    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
