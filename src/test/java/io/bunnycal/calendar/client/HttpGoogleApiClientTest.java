package io.bunnycal.calendar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpGoogleApiClientTest {

    @Test
    void createBody_containsOnlyGuestAttendee_andOmitsConferenceRequestByDefault() {
        CreateEventRequest request = new CreateEventRequest(
                UUID.randomUUID(),
                "Intro",
                "bookingId=1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:30:00Z"),
                "host@example.com",
                "guest@example.com",
                "Guest User",
                "google:1");

        Map<String, Object> body = HttpGoogleApiClient.buildCreateEventBody(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> start = (Map<String, Object>) body.get("start");
        @SuppressWarnings("unchecked")
        Map<String, Object> end = (Map<String, Object>) body.get("end");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attendees = (List<Map<String, Object>>) body.get("attendees");
        assertEquals("2026-05-10T10:00:00Z", start.get("dateTime"));
        assertEquals("2026-05-10T10:30:00Z", end.get("dateTime"));
        assertEquals(1, attendees.size());
        assertEquals("guest@example.com", attendees.get(0).get("email"));
        assertEquals("Guest User", attendees.get(0).get("displayName"));
        assertFalse(body.containsKey("conferenceData"));
        assertEquals("/calendar/v3/calendars/primary/events?sendUpdates=none&conferenceDataVersion=1",
                HttpGoogleApiClient.CREATE_EVENT_URI);
    }

    @Test
    void updateBody_containsAttendee_andOmitsConferenceRequestByDefault() {
        UpdateEventRequest request = new UpdateEventRequest(
                UUID.randomUUID(),
                "ext-1",
                "Intro",
                "bookingId=1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:30:00Z"),
                "host@example.com",
                "guest@example.com",
                "Guest User");

        Map<String, Object> body = HttpGoogleApiClient.buildUpdateEventBody(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> start = (Map<String, Object>) body.get("start");
        @SuppressWarnings("unchecked")
        Map<String, Object> end = (Map<String, Object>) body.get("end");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attendees = (List<Map<String, Object>>) body.get("attendees");
        assertEquals("2026-05-10T10:00:00Z", start.get("dateTime"));
        assertEquals("2026-05-10T10:30:00Z", end.get("dateTime"));
        assertEquals(1, attendees.size());
        assertEquals("guest@example.com", attendees.get(0).get("email"));
        assertFalse(body.containsKey("conferenceData"));
        assertEquals("/calendar/v3/calendars/primary/events/{id}?sendUpdates=none&conferenceDataVersion=1",
                HttpGoogleApiClient.UPDATE_EVENT_URI);
    }

    @Test
    void createBody_includesConferenceRequestWhenNativeMeetIsRequested() {
        CreateEventRequest request = new CreateEventRequest(
                UUID.randomUUID(),
                "Intro",
                "bookingId=1",
                Instant.parse("2026-05-10T10:00:00Z"),
                Instant.parse("2026-05-10T10:30:00Z"),
                "host@example.com",
                "guest@example.com",
                "Guest User",
                "google:1",
                "primary",
                ConferencingInstruction.requestNativeMeet(ConferencingProviderType.GOOGLE_MEET));

        Map<String, Object> body = HttpGoogleApiClient.buildCreateEventBody(request);
        assertTrue(body.containsKey("conferenceData"));
    }

    @Test
    void diagnoseWarning_flagsAccountMismatch() {
        String warning = HttpGoogleApiClient.diagnoseWarning(
                Map.of("id", "evt-1"),
                Map.of("status", "confirmed", "htmlLink", "https://calendar.google.com/event?eid=1"),
                "host@example.com",
                "host@example.com",
                "different@example.com",
                "guest@example.com",
                true
        );
        assertEquals("OAUTH_ACCOUNT_MISMATCH", warning);
    }

    @Test
    void diagnoseWarning_flagsMissingAttendee() {
        String warning = HttpGoogleApiClient.diagnoseWarning(
                Map.of("id", "evt-1"),
                Map.of("status", "confirmed", "htmlLink", "https://calendar.google.com/event?eid=1"),
                "host@example.com",
                "host@example.com",
                "host@example.com",
                "guest@example.com",
                false
        );
        assertEquals("GUEST_ATTENDEE_MISSING", warning);
    }

    @Test
    void cancelledEventBody_isTreatedAsMissingForObserve() {
        assertTrue(HttpGoogleApiClient.isCancelledEventBody(Map.of("status", "cancelled")));
        assertTrue(HttpGoogleApiClient.isCancelledEventBody(Map.of("status", "CANCELLED")));
    }

    @Test
    void encodeGoogleCalendarIdForPath_emailStyle() {
        assertEquals("koushikruidas%40gmail.com",
                HttpGoogleApiClient.encodeGoogleCalendarIdForPath("koushikruidas@gmail.com"));
    }

    @Test
    void encodeGoogleCalendarIdForPath_groupCalendar() {
        assertEquals("family17130278116817796873%40group.calendar.google.com",
                HttpGoogleApiClient.encodeGoogleCalendarIdForPath("family17130278116817796873@group.calendar.google.com"));
    }

    @Test
    void encodeGoogleCalendarIdForPath_holidayCalendar() {
        assertEquals("en.indian%23holiday%40group.v.calendar.google.com",
                HttpGoogleApiClient.encodeGoogleCalendarIdForPath("en.indian#holiday@group.v.calendar.google.com"));
    }

    @Test
    void encodeGoogleCalendarIdForPath_noDoubleEncoding() {
        assertEquals("koushikruidas%40gmail.com",
                HttpGoogleApiClient.encodeGoogleCalendarIdForPath("koushikruidas%40gmail.com"));
    }

    @Test
    void normalizeGoogleCalendarId_stripsMailtoMarkdownWrapper() {
        assertEquals("koushikruidas@gmail.com",
                HttpGoogleApiClient.normalizeGoogleCalendarId("[koushikruidas@gmail.com](mailto:koushikruidas@gmail.com)"));
    }
}
