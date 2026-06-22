package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoundRobinSlotTokenServiceTest {

    @Test
    void issueAndVerify_roundTripsOwnershipPayload() {
        RoundRobinSlotTokenService service = new RoundRobinSlotTokenService("test-secret");
        UUID ownerId = UUID.randomUUID();
        UUID eventTypeId = UUID.randomUUID();
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        Instant start = Instant.parse("2026-06-15T09:00:00Z");
        Instant end = Instant.parse("2026-06-15T09:30:00Z");

        String token = service.issue(ownerId, eventTypeId, start, end, List.of(aliceId, bobId));
        var decoded = service.verify(token);

        assertEquals(ownerId, decoded.ownerUserId());
        assertEquals(eventTypeId, decoded.eventTypeId());
        assertEquals(start, decoded.start());
        assertEquals(end, decoded.end());
        assertEquals(List.of(aliceId, bobId), decoded.candidateParticipantIds());
    }

    @Test
    void verify_rejectsTamperedToken() {
        RoundRobinSlotTokenService service = new RoundRobinSlotTokenService("test-secret");
        String token = service.issue(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                List.of(UUID.randomUUID()));

        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

        CustomException ex = assertThrows(CustomException.class, () -> service.verify(tampered));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }
}
