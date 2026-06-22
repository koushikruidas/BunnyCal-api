package io.bunnycal.calendar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpGoogleApiClientTest {

    @Test
    void createBody_omitsAttendees_andOmitsConferenceRequestByDefault() {
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
        assertEquals("2026-05-10T10:00:00Z", start.get("dateTime"));
        assertEquals("2026-05-10T10:30:00Z", end.get("dateTime"));
        assertFalse(body.containsKey("attendees"));
        assertFalse(body.containsKey("conferenceData"));
        assertEquals("/calendar/v3/calendars/primary/events?sendUpdates=none&conferenceDataVersion=1",
                HttpGoogleApiClient.CREATE_EVENT_URI);
    }

    @Test
    void updateBody_omitsAttendees_andOmitsConferenceRequestByDefault() {
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
        assertEquals("2026-05-10T10:00:00Z", start.get("dateTime"));
        assertEquals("2026-05-10T10:30:00Z", end.get("dateTime"));
        assertFalse(body.containsKey("attendees"));
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
    void normalizeGoogleCalendarId_stripsMailtoMarkdownWrapper() {
        assertEquals("koushikruidas@gmail.com",
                HttpGoogleApiClient.normalizeGoogleCalendarId("[koushikruidas@gmail.com](mailto:koushikruidas@gmail.com)"));
    }
}
