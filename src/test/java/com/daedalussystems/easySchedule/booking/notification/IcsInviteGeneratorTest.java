package com.daedalussystems.easySchedule.booking.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IcsInviteGeneratorTest {

    private final IcsInviteGenerator generator = new IcsInviteGenerator("example.com");

    @Test
    void standaloneRequest_containsAuthoritativeInviteFields() {
        UUID bookingId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String ics = generator.buildStandaloneRequest(
                bookingId,
                "Discovery",
                "Booking 1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:30:00Z"),
                "Host Name",
                "host@example.com",
                "Host Name",
                "host@example.com",
                "Guest Name",
                "guest@example.com",
                0
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("METHOD:REQUEST"));
        assertTrue(unfolded.contains("UID:booking-11111111-2222-3333-4444-555555555555@example.com"));
        assertTrue(unfolded.contains("SEQUENCE:0"));
        assertTrue(unfolded.contains("ORGANIZER;CN=Host Name:mailto:host@example.com"));
        assertTrue(unfolded.contains("ATTENDEE;CN=Guest Name;RSVP=TRUE;PARTSTAT=NEEDS-ACTION"));
    }

    @Test
    void connectedSnapshot_usesPublishAndNoAttendee() {
        String ics = generator.buildConnectedSnapshot(
                UUID.randomUUID(),
                "Discovery",
                "Booking 1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:30:00Z"),
                "Host Name",
                "host@example.com",
                1
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("METHOD:PUBLISH"));
        assertTrue(unfolded.contains("SEQUENCE:1"));
        assertFalse(unfolded.contains("ATTENDEE;"));
    }

    @Test
    void standaloneCancel_setsCancelMethodAndStatus() {
        String ics = generator.buildStandaloneCancel(
                UUID.randomUUID(),
                "Discovery",
                "Booking 1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:30:00Z"),
                "Host Name",
                "host@example.com",
                "Host Name",
                "host@example.com",
                "Guest Name",
                "guest@example.com",
                2
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("METHOD:CANCEL"));
        assertTrue(unfolded.contains("STATUS:CANCELLED"));
        assertTrue(unfolded.contains("SEQUENCE:2"));
    }

    @Test
    void standaloneRequest_includesBothParticipantsAsRsvpAttendees() {
        String ics = generator.buildStandaloneRequest(
                UUID.randomUUID(),
                "Discovery",
                "Booking 1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:30:00Z"),
                "App Calendar",
                "calendar@example.com",
                "Host Name",
                "host@example.com",
                "Guest Name",
                "guest@example.com",
                3
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("METHOD:REQUEST"));
        assertTrue(unfolded.contains("ORGANIZER;CN=App Calendar:mailto:calendar@example.com"));
        assertTrue(unfolded.contains("ATTENDEE;CN=Host Name;RSVP=TRUE;PARTSTAT=NEEDS-ACTION"));
        assertTrue(unfolded.contains("ATTENDEE;CN=Guest Name;RSVP=TRUE;PARTSTAT=NEEDS-ACTION"));
        assertTrue(unfolded.contains("mailto:host@example.com"));
        assertTrue(unfolded.contains("mailto:guest@example.com"));
    }

    private static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }
}
