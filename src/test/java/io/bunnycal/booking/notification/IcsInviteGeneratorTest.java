package io.bunnycal.booking.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.bunnycal.conferencing.service.ConferenceDetails;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
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
                conf(null)
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
                conf(null)
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
                conf(null)
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("METHOD:REQUEST"));
        assertTrue(unfolded.contains("ORGANIZER;CN=App Calendar:mailto:calendar@example.com"));
        assertTrue(unfolded.contains("ATTENDEE;CN=Host Name;CUTYPE=INDIVIDUAL;ROLE=CHAIR;PARTSTAT=ACCEPTED;RSVP=FALSE:mailto:host@example.com"));
        assertTrue(unfolded.contains("ATTENDEE;CN=Guest Name;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest@example.com"));
    }

    @Test
    void standaloneRequest_embedsConferenceUrlAsLocationForGoogleMeet() {
        String joinUrl = "https://meet.google.com/abc-defg-hij";
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
                conf(joinUrl, "GOOGLE_MEET")
        );
        String unfolded = unfold(ics);

        assertTrue(unfolded.contains("LOCATION:" + joinUrl));
        assertTrue(unfolded.contains("URL:" + joinUrl));
        // Provider-matched hint only: Google Meet emits X-GOOGLE-CONFERENCE, never the Teams hint.
        assertTrue(unfolded.contains("X-GOOGLE-CONFERENCE:" + joinUrl));
        assertFalse(unfolded.contains("X-MICROSOFT-SKYPETEAMSMEETINGURL"));
        assertTrue(unfolded.contains("Join: " + joinUrl));
        assertTrue(unfolded.contains("X-MICROSOFT-CDO-BUSYSTATUS:BUSY"));
    }

    @Test
    void standaloneRequest_customUrlEmitsLocationButNoProviderHints() {
        String joinUrl = "https://meet.example.com/custom-room";
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
                conf(joinUrl, "CUSTOM_URL")
        );
        String unfolded = unfold(ics);

        // The link is surfaced via LOCATION/URL/DESCRIPTION, but NO provider-specific
        // conference hints are emitted — a custom URL is neither a Meet nor a Teams meeting.
        // Emitting a foreign provider hint makes Outlook misparse/suppress the invite.
        assertTrue(unfolded.contains("LOCATION:" + joinUrl));
        assertTrue(unfolded.contains("URL:" + joinUrl));
        assertTrue(unfolded.contains("Join: " + joinUrl));
        assertFalse(unfolded.contains("X-GOOGLE-CONFERENCE"));
        assertFalse(unfolded.contains("X-MICROSOFT-SKYPETEAMSMEETINGURL"));
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
                conf("")
        );
        String unfolded = unfold(ics);

        assertFalse(unfolded.contains("LOCATION:"));
        assertFalse(unfolded.contains("URL:"));
        assertFalse(unfolded.contains("X-MICROSOFT-SKYPETEAMSMEETINGURL"));
        assertFalse(unfolded.contains("X-GOOGLE-CONFERENCE"));
    }

    @Test
    void lifecycleUidAndOrganizerRemainStableAcrossRequestUpdateCancel() {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.parse("2026-06-10T10:00:00Z");
        Instant end = Instant.parse("2026-06-10T10:30:00Z");
        String request = unfold(generator.buildStandaloneRequest(
                bookingId, "Summary", "Desc", start, end,
                "App Calendar", "calendar@example.com",
                "Host", "host@example.com", "Guest", "guest@example.com", 0,
                conf("https://zoom.us/j/111")));
        String update = unfold(generator.buildStandaloneRequest(
                bookingId, "Summary", "Desc2", start.plusSeconds(1800), end.plusSeconds(1800),
                "App Calendar", "calendar@example.com",
                "Host", "host@example.com", "Guest", "guest@example.com", 1,
                conf("https://zoom.us/j/111")));
        String cancel = unfold(generator.buildStandaloneCancel(
                bookingId, "Summary", "Desc2", start.plusSeconds(1800), end.plusSeconds(1800),
                "App Calendar", "calendar@example.com",
                "Host", "host@example.com", "Guest", "guest@example.com", 2,
                conf("https://zoom.us/j/111")));

        String uid = "UID:booking-" + bookingId + "@example.com";
        String organizer = "ORGANIZER;CN=App Calendar:mailto:calendar@example.com";
        assertTrue(request.contains(uid));
        assertTrue(update.contains(uid));
        assertTrue(cancel.contains(uid));
        assertTrue(request.contains(organizer));
        assertTrue(update.contains(organizer));
        assertTrue(cancel.contains(organizer));
        assertTrue(request.contains("METHOD:REQUEST"));
        assertTrue(update.contains("METHOD:REQUEST"));
        assertTrue(cancel.contains("METHOD:CANCEL"));
        assertTrue(cancel.contains("STATUS:CANCELLED"));
        assertTrue(request.contains("SEQUENCE:0"));
        assertTrue(update.contains("SEQUENCE:1"));
        assertTrue(cancel.contains("SEQUENCE:2"));
    }

    @Test
    void fixtureConferenceProvidersRenderConsistentCalendarFields() {
        UUID bookingId = UUID.randomUUID();
        // url -> provider, plus the expected provider-specific hint header (or null for none).
        String[][] fixtures = {
                {"https://meet.google.com/abc-defg-hij", "GOOGLE_MEET", "X-GOOGLE-CONFERENCE"},
                {"https://teams.microsoft.com/l/meetup-join/xyz", "MICROSOFT_TEAMS", "X-MICROSOFT-SKYPETEAMSMEETINGURL"},
                {"https://zoom.us/j/123456", "ZOOM", null},
                {"https://example.com/custom-room", "CUSTOM_URL", null}
        };
        for (String[] fixture : fixtures) {
            String url = fixture[0];
            String provider = fixture[1];
            String expectedHint = fixture[2];
            String request = unfold(generator.buildStandaloneRequest(
                    bookingId, "Summary", "Desc",
                    Instant.parse("2026-06-10T10:00:00Z"),
                    Instant.parse("2026-06-10T10:30:00Z"),
                    "App Calendar", "calendar@example.com",
                    "Host", "host@example.com", "Guest", "guest@example.com", 5, conf(url, provider)));
            String cancel = unfold(generator.buildStandaloneCancel(
                    bookingId, "Summary", "Desc",
                    Instant.parse("2026-06-10T10:00:00Z"),
                    Instant.parse("2026-06-10T10:30:00Z"),
                    "App Calendar", "calendar@example.com",
                    "Host", "host@example.com", "Guest", "guest@example.com", 6, conf(url, provider)));
            // The link is always surfaced via URL regardless of provider.
            assertTrue(request.contains("URL:" + url));
            assertTrue(cancel.contains("URL:" + url));
            // Only the matching provider hint is emitted; foreign hints must be absent.
            for (String hint : new String[]{"X-GOOGLE-CONFERENCE", "X-MICROSOFT-SKYPETEAMSMEETINGURL"}) {
                if (hint.equals(expectedHint)) {
                    assertTrue(request.contains(hint + ":" + url), provider + " should emit " + hint);
                    assertTrue(cancel.contains(hint + ":" + url), provider + " should emit " + hint);
                } else {
                    assertFalse(request.contains(hint), provider + " must not emit " + hint);
                    assertFalse(cancel.contains(hint), provider + " must not emit " + hint);
                }
            }
        }
        assertEquals(4, fixtures.length);
    }

    @Test
    void dstBoundary_utcInstantsRemainStableInIcs() {
        ZonedDateTime nyBefore = ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, ZoneId.of("America/New_York"));
        Instant start = nyBefore.toInstant();
        Instant end = nyBefore.plusHours(1).toInstant();
        String unfolded = unfold(generator.buildStandaloneRequest(
                UUID.randomUUID(),
                "DST",
                "Boundary",
                start,
                end,
                "App Calendar",
                "calendar@example.com",
                "Host",
                "host@example.com",
                "Guest",
                "guest@example.com",
                1,
                conf(null)
        ));
        assertTrue(unfolded.contains("DTSTART:" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(start)));
        assertTrue(unfolded.contains("DTEND:" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(end)));
    }

    @Test
    void collectiveRequest_allAttendeesAreReqParticipant() {
        // Option B: collective hosts and guest both use REQ-PARTICIPANT. No CHAIR.
        UUID bookingId = UUID.randomUUID();
        String ics = unfold(generator.buildCollectiveRequest(
                bookingId,
                "Team Sync",
                "Booking " + bookingId,
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                "BunnyCal Calendar",
                "calendar@example.com",
                List.of(
                        new IcsInviteGenerator.CollectiveHost("Alice", "alice@example.com"),
                        new IcsInviteGenerator.CollectiveHost("Bob", "bob@example.com"),
                        new IcsInviteGenerator.CollectiveHost("Charlie", "charlie@example.com")
                ),
                "Guest Name",
                "guest@example.com",
                0,
                conf(null)
        ));

        assertTrue(ics.contains("METHOD:REQUEST"));
        assertTrue(ics.contains("ORGANIZER;CN=BunnyCal Calendar:mailto:calendar@example.com"));
        // All three hosts present as REQ-PARTICIPANT
        assertTrue(ics.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:alice@example.com"), "alice present");
        assertTrue(ics.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:bob@example.com"), "bob present");
        assertTrue(ics.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:charlie@example.com"), "charlie present");
        // Guest present as REQ-PARTICIPANT
        assertTrue(ics.contains("ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:guest@example.com"), "guest present");
        // No CHAIR anywhere in a collective ICS (covered by companion test below, also asserted here)
        assertFalse(ics.contains("ROLE=CHAIR"), "collective ICS must not contain ROLE=CHAIR");
        // Exactly 4 ATTENDEE lines (3 hosts + 1 guest)
        int attendeeCount = 0;
        int idx = 0;
        while ((idx = ics.indexOf("ATTENDEE;", idx)) >= 0) { attendeeCount++; idx++; }
        assertEquals(4, attendeeCount, "expected 4 ATTENDEE lines");
    }

    @Test
    void collectiveRequest_noCHAIREmittedForAnyAttendee() {
        // Guard: verify ROLE=CHAIR never appears in a collective ICS regardless of host count.
        String request = unfold(generator.buildCollectiveRequest(
                UUID.randomUUID(), "S", "D",
                Instant.parse("2026-06-15T09:00:00Z"), Instant.parse("2026-06-15T09:30:00Z"),
                "BunnyCal Calendar", "calendar@example.com",
                List.of(
                        new IcsInviteGenerator.CollectiveHost("H1", "h1@example.com"),
                        new IcsInviteGenerator.CollectiveHost("H2", "h2@example.com")
                ),
                "Guest", "guest@example.com", 0, conf(null)));
        String cancel = unfold(generator.buildCollectiveCancel(
                UUID.randomUUID(), "S", "D",
                Instant.parse("2026-06-15T09:00:00Z"), Instant.parse("2026-06-15T09:30:00Z"),
                "BunnyCal Calendar", "calendar@example.com",
                List.of(
                        new IcsInviteGenerator.CollectiveHost("H1", "h1@example.com"),
                        new IcsInviteGenerator.CollectiveHost("H2", "h2@example.com")
                ),
                "Guest", "guest@example.com", 1, conf(null)));

        assertFalse(request.contains("ROLE=CHAIR"), "REQUEST must not contain ROLE=CHAIR");
        assertFalse(cancel.contains("ROLE=CHAIR"),  "CANCEL must not contain ROLE=CHAIR");
    }

    @Test
    void collectiveCancel_setsMethodCancelAndStatusCancelled() {
        UUID bookingId = UUID.randomUUID();
        String ics = unfold(generator.buildCollectiveCancel(
                bookingId,
                "Team Sync",
                "Booking " + bookingId,
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                "BunnyCal Calendar",
                "calendar@example.com",
                List.of(
                        new IcsInviteGenerator.CollectiveHost("Alice", "alice@example.com"),
                        new IcsInviteGenerator.CollectiveHost("Bob", "bob@example.com")
                ),
                "Guest Name",
                "guest@example.com",
                1,
                conf(null)
        ));

        assertTrue(ics.contains("METHOD:CANCEL"));
        assertTrue(ics.contains("STATUS:CANCELLED"));
        assertTrue(ics.contains("SEQUENCE:1"));
    }

    @Test
    void collectiveRequest_deduplicatesHostEmailEqualToOrganizerEmail() {
        // If a host's email equals the organizer email, that host is skipped
        // (same rule as buildStandaloneRequest via addAttendee).
        String ics = unfold(generator.buildCollectiveRequest(
                UUID.randomUUID(),
                "Sync", "Desc",
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:30:00Z"),
                "BunnyCal Calendar",
                "calendar@example.com",
                List.of(
                        new IcsInviteGenerator.CollectiveHost("Alice", "alice@example.com"),
                        new IcsInviteGenerator.CollectiveHost("Organizer", "calendar@example.com")
                ),
                "Guest", "guest@example.com",
                0, conf(null)
        ));

        assertTrue(ics.contains("mailto:alice@example.com"), "alice present");
        // ORGANIZER line legitimately contains calendar@example.com — only ATTENDEE lines must not.
        boolean organizerAppearsAsAttendee = java.util.Arrays.stream(ics.split("\r\n"))
                .filter(l -> l.startsWith("ATTENDEE"))
                .anyMatch(l -> l.contains("calendar@example.com"));
        assertFalse(organizerAppearsAsAttendee, "organizer email must not appear on any ATTENDEE line");
        assertTrue(ics.contains("mailto:guest@example.com"), "guest present");
    }

    private static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }

    private static ConferenceDetails conf(String joinUrl) {
        return conf(joinUrl, "UNKNOWN");
    }

    private static ConferenceDetails conf(String joinUrl, String provider) {
        if (joinUrl == null || joinUrl.isBlank()) {
            return ConferenceDetails.none("test", Instant.now());
        }
        return new ConferenceDetails(provider, joinUrl, null, null, null, java.util.Map.of(), "test", Instant.now());
    }
}
