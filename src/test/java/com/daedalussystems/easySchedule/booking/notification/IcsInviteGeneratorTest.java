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
                "App Calendar",
                "calendar@example.com",
                "Host Name",
                "host@example.com",
                "Guest Name",
                "guest@example.com",
                0,
                null
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("METHOD:REQUEST"));
        assertTrue(unfolded.contains("UID:booking-11111111-2222-3333-4444-555555555555@example.com"));
        assertTrue(unfolded.contains("SEQUENCE:0"));
        assertTrue(unfolded.contains("ORGANIZER;CN=App Calendar:mailto:calendar@example.com"));
        assertTrue(unfolded.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest@example.com"));
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
                "App Calendar",
                "calendar@example.com",
                "Host Name",
                "host@example.com",
                "Guest Name",
                "guest@example.com",
                2,
                null
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("METHOD:CANCEL"));
        assertTrue(unfolded.contains("STATUS:CANCELLED"));
        assertTrue(unfolded.contains("SEQUENCE:2"));
    }

    @Test
    void standaloneRequest_marksHostAsChairAndGuestAsReqParticipant() {
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
                3,
                null
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("METHOD:REQUEST"));
        assertTrue(unfolded.contains("ORGANIZER;CN=App Calendar:mailto:calendar@example.com"));
        assertTrue(unfolded.contains("ATTENDEE;CN=Host Name;CUTYPE=INDIVIDUAL;ROLE=CHAIR;PARTSTAT=ACCEPTED;RSVP=FALSE:mailto:host@example.com"));
        assertTrue(unfolded.contains("ATTENDEE;CN=Guest Name;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest@example.com"));
    }

    @Test
    void standaloneRequest_embedsConferenceUrlAsLocationAndExtensionProperties() {
        String joinUrl = "https://zoom.us/j/1234567890?pwd=abc";
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
                4,
                joinUrl
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("LOCATION:" + joinUrl));
        assertTrue(unfolded.contains("URL:" + joinUrl));
        assertTrue(unfolded.contains("X-MICROSOFT-SKYPETEAMSMEETINGURL:" + joinUrl));
        assertTrue(unfolded.contains("X-GOOGLE-CONFERENCE:" + joinUrl));
        assertTrue(unfolded.contains("Join: " + joinUrl));
        assertTrue(unfolded.contains("X-MICROSOFT-CDO-BUSYSTATUS:BUSY"));
    }

    @Test
    void standaloneRequest_omitsConferenceFieldsWhenJoinUrlAbsent() {
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
                5,
                ""
        );
        String unfolded = unfold(ics);

        assertFalse(unfolded.contains("LOCATION:"));
        assertFalse(unfolded.contains("URL:"));
        assertFalse(unfolded.contains("X-MICROSOFT-SKYPETEAMSMEETINGURL"));
        assertFalse(unfolded.contains("X-GOOGLE-CONFERENCE"));
    }

    private static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }
}
