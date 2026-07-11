package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectiveSlotTokenServiceTest {

    private static final String SECRET = "test-collective-secret";

    @Test
    void issueAndVerify_roundTripsAllFields() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        UUID ownerId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Instant start = Instant.parse("2026-06-15T09:00:00Z");
        Instant end = Instant.parse("2026-06-15T09:30:00Z");

        String token = service.issue(ownerId, eventTypeId, start, end, List.of(alice, bob));
        CollectiveSlotTokenService.DecodedCollectiveToken decoded = service.verify(token);

        assertEquals(ownerId, decoded.ownerUserId());
        assertEquals(eventTypeId, decoded.eventTypeId());
        assertEquals(start, decoded.start());
        assertEquals(end, decoded.end());
        assertEquals(service.rosterHash(List.of(alice, bob)), decoded.rosterHash());
    }

    @Test
    void verify_rejectsTamperedToken() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        String token = service.issue(
                UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                List.of(UUID.randomUUID()));

        // Tamper the payload segment, not the signature's last character: the signature is a
        // 32-byte HMAC encoded as 43 unpadded base64url chars, whose final char carries only 4
        // significant bits. Flipping just that char often leaves the decoded bytes unchanged,
        // so the token would still verify and this assertion would flake.
        String[] parts = token.split("\\.", 2);
        String tamperedPayload = (parts[0].startsWith("A") ? "B" : "A") + parts[0].substring(1);
        String tampered = tamperedPayload + "." + parts[1];

        CustomException ex = assertThrows(CustomException.class, () -> service.verify(tampered));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void verify_rejectsNullToken() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        assertThrows(CustomException.class, () -> service.verify(null));
    }

    @Test
    void verify_rejectsBlankToken() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        assertThrows(CustomException.class, () -> service.verify("  "));
    }

    @Test
    void verify_rejectsTokenSignedWithDifferentSecret() {
        CollectiveSlotTokenService a = new CollectiveSlotTokenService("secret-a");
        CollectiveSlotTokenService b = new CollectiveSlotTokenService("secret-b");
        String token = a.issue(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                List.of(UUID.randomUUID()));

        assertThrows(CustomException.class, () -> b.verify(token));
    }

    @Test
    void rosterHash_isOrderIndependent() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertEquals(
                service.rosterHash(List.of(alice, bob)),
                service.rosterHash(List.of(bob, alice)));
    }

    @Test
    void rosterHash_changesWhenParticipantAdded() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();

        String twoParticipants = service.rosterHash(List.of(alice, bob));
        String threeParticipants = service.rosterHash(List.of(alice, bob, charlie));

        assertNotEquals(twoParticipants, threeParticipants);
    }

    @Test
    void rosterHash_changesWhenParticipantReplaced() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();

        assertNotEquals(
                service.rosterHash(List.of(alice, bob)),
                service.rosterHash(List.of(alice, charlie)));
    }

    @Test
    void validateRosterMatch_passesWhenRosterUnchanged() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        List<UUID> roster = List.of(alice, bob);

        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                roster);
        CollectiveSlotTokenService.DecodedCollectiveToken decoded = service.verify(token);

        // Should not throw
        service.validateRosterMatch(decoded, roster);
        service.validateRosterMatch(decoded, List.of(bob, alice)); // order-independent
    }

    @Test
    void validateRosterMatch_failsWhenParticipantAdded() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();

        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                List.of(alice, bob));
        CollectiveSlotTokenService.DecodedCollectiveToken decoded = service.verify(token);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.validateRosterMatch(decoded, List.of(alice, bob, charlie)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void validateRosterMatch_failsWhenParticipantRemoved() {
        CollectiveSlotTokenService service = new CollectiveSlotTokenService(SECRET);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                List.of(alice, bob));
        CollectiveSlotTokenService.DecodedCollectiveToken decoded = service.verify(token);

        CustomException ex = assertThrows(CustomException.class,
                () -> service.validateRosterMatch(decoded, List.of(alice)));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void crossTypeIsolation_rrTokenRejectedByCollective() {
        RoundRobinSlotTokenService rrService = new RoundRobinSlotTokenService(SECRET);
        CollectiveSlotTokenService collService = new CollectiveSlotTokenService(SECRET);

        String rrToken = rrService.issue(
                UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                List.of(UUID.randomUUID()));

        // Even with same secret, RR token must not verify as a collective token
        // (different version prefix cv1 vs v1, different field count).
        assertThrows(CustomException.class, () -> collService.verify(rrToken));
    }
}
