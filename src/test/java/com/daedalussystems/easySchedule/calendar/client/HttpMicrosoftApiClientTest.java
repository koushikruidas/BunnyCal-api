package com.daedalussystems.easySchedule.calendar.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.daedalussystems.easySchedule.calendar.config.MicrosoftOAuthProperties;
import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingInstruction;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpMicrosoftApiClientTest {

    private MockRestServiceServer mockServer;
    private HttpMicrosoftApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        MicrosoftOAuthProperties props = new MicrosoftOAuthProperties();
        props.setClientId("test-client");
        props.setClientSecret("test-secret");
        props.setFrontendBaseUrl("https://app.example.com");
        props.setFrontendSuccessPath("/oauth/success");
        props.setFrontendErrorPath("/oauth/error");
        client = new HttpMicrosoftApiClient(builder, props);
    }

    // ── CREATE ──────────────────────────────────────────────────────────────

    @Test
    void createEvent_nullCalendarId_usesDefaultEventsPath() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"evt-1","webLink":"https://outlook.live.com/event/1",
                         "onlineMeeting":{"joinUrl":"https://teams.microsoft.com/l/meetup/1"}}
                        """, MediaType.APPLICATION_JSON));

        var result = client.createEvent("token", createRequest(null));

        assertEquals("evt-1", result.externalEventId());
        assertEquals("https://outlook.live.com/event/1", result.providerEventUrl());
        assertEquals("https://teams.microsoft.com/l/meetup/1", result.conferenceUrl());
        mockServer.verify();
    }

    @Test
    void createEvent_blankCalendarId_usesDefaultEventsPath() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"evt-2\"}", MediaType.APPLICATION_JSON));

        client.createEvent("token", createRequest("  "));
        mockServer.verify();
    }

    @Test
    void createEvent_primaryCalendarId_usesDefaultEventsPath() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"evt-3\"}", MediaType.APPLICATION_JSON));

        // "primary" is Google semantics — must NOT be forwarded as a Graph calendar ID
        client.createEvent("token", createRequest("primary"));
        mockServer.verify();
    }

    @Test
    void createEvent_explicitCalendarId_usesCalendarsPath() {
        String calId = "AAMkAGI2NGVhNTM3LTRmZjAtNDljMS05Mzk2LTZhZmU0MGU1OGZhNgAuAAAAAADUuTJK1K9aTpCdqXop";
        String encodedId = java.net.URLEncoder.encode(calId, java.nio.charset.StandardCharsets.UTF_8);
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/calendars/" + encodedId + "/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"evt-4\"}", MediaType.APPLICATION_JSON));

        client.createEvent("token", createRequest(calId));
        mockServer.verify();
    }

    @Test
    void createEvent_withTeamsConferencing_includesOnlineMeetingFields() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.isOnlineMeeting").value(true))
                .andExpect(jsonPath("$.onlineMeetingProvider").value("teamsForBusiness"))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andRespond(withSuccess("""
                        {"id":"evt-teams","onlineMeeting":{"joinUrl":"https://teams.microsoft.com/l/meet/x"}}
                        """, MediaType.APPLICATION_JSON));

        ConferencingInstruction teams = ConferencingInstruction.requestNativeMeet(ConferencingProviderType.MICROSOFT_TEAMS);
        var result = client.createEvent("token", new CreateEventRequest(
                UUID.randomUUID(), "Standup", "desc",
                Instant.parse("2026-05-24T09:00:00Z"), Instant.parse("2026-05-24T09:30:00Z"),
                "host@example.com", "guest@example.com", "Guest", "idem-1", null, teams));

        assertEquals("https://teams.microsoft.com/l/meet/x", result.conferenceUrl());
        mockServer.verify();
    }

    // ── URL_EMBEDDED conferencing on Microsoft authoritative ────────────────

    @Test
    void createEvent_withZoomConferencing_embedsJoinUrlInBodyAndLocation() {
        String joinUrl = "https://example.zoom.us/j/1234567890?pwd=abc";
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.location.displayName").value(joinUrl))
                .andExpect(jsonPath("$.body.content").value(org.hamcrest.Matchers.containsString(joinUrl)))
                .andExpect(jsonPath("$.isOnlineMeeting").doesNotExist())
                .andExpect(jsonPath("$.onlineMeetingProvider").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"evt-zoom\"}", MediaType.APPLICATION_JSON));

        ConferencingInstruction zoom = ConferencingInstruction.urlEmbedded(
                ConferencingProviderType.ZOOM, joinUrl, "https://example.zoom.us/host/1", "1234567890");
        client.createEvent("token", new CreateEventRequest(
                UUID.randomUUID(), "Standup", "bookingId=abc",
                Instant.parse("2026-05-24T09:00:00Z"), Instant.parse("2026-05-24T09:30:00Z"),
                "host@example.com", "guest@example.com", "Guest", "idem-1", null, zoom));
        mockServer.verify();
    }

    @Test
    void createEvent_withGoogleMeetEmbedded_embedsJoinUrl() {
        String joinUrl = "https://meet.google.com/abc-defg-hij";
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.location.displayName").value(joinUrl))
                .andExpect(jsonPath("$.body.content").value(org.hamcrest.Matchers.containsString(joinUrl)))
                .andExpect(jsonPath("$.isOnlineMeeting").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"evt-meet\"}", MediaType.APPLICATION_JSON));

        ConferencingInstruction meet = ConferencingInstruction.urlEmbedded(
                ConferencingProviderType.GOOGLE_MEET, joinUrl, null, null);
        client.createEvent("token", new CreateEventRequest(
                UUID.randomUUID(), "Standup", "bookingId=abc",
                Instant.parse("2026-05-24T09:00:00Z"), Instant.parse("2026-05-24T09:30:00Z"),
                "host@example.com", "guest@example.com", "Guest", "idem-2", null, meet));
        mockServer.verify();
    }

    @Test
    void createEvent_withCustomUrl_embedsJoinUrl() {
        String joinUrl = "https://conf.example.com/room/xyz";
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.location.displayName").value(joinUrl))
                .andExpect(jsonPath("$.body.content").value(org.hamcrest.Matchers.containsString(joinUrl)))
                .andExpect(jsonPath("$.isOnlineMeeting").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"evt-custom\"}", MediaType.APPLICATION_JSON));

        ConferencingInstruction custom = ConferencingInstruction.urlEmbedded(
                ConferencingProviderType.CUSTOM_URL, joinUrl, null, null);
        client.createEvent("token", new CreateEventRequest(
                UUID.randomUUID(), "Standup", "bookingId=abc",
                Instant.parse("2026-05-24T09:00:00Z"), Instant.parse("2026-05-24T09:30:00Z"),
                "host@example.com", "guest@example.com", "Guest", "idem-3", null, custom));
        mockServer.verify();
    }

    @Test
    void createEvent_withNoneConferencing_doesNotSetLocationOrTeamsFields() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.isOnlineMeeting").doesNotExist())
                .andExpect(jsonPath("$.onlineMeetingProvider").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"evt-none\"}", MediaType.APPLICATION_JSON));

        client.createEvent("token", createRequest(null));
        mockServer.verify();
    }

    @Test
    void updateEvent_withZoomConferencing_embedsJoinUrlInBodyAndLocation() {
        String joinUrl = "https://example.zoom.us/j/9876543210";
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events/evt-zoom-upd"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.location.displayName").value(joinUrl))
                .andExpect(jsonPath("$.body.content").value(org.hamcrest.Matchers.containsString(joinUrl)))
                .andRespond(withSuccess("{\"id\":\"evt-zoom-upd\"}", MediaType.APPLICATION_JSON));

        ConferencingInstruction zoom = ConferencingInstruction.urlEmbedded(
                ConferencingProviderType.ZOOM, joinUrl, null, "9876543210");
        client.updateEvent("token", new UpdateEventRequest(
                UUID.randomUUID(), "evt-zoom-upd", "Standup", "bookingId=abc",
                Instant.parse("2026-05-24T09:00:00Z"), Instant.parse("2026-05-24T09:30:00Z"),
                "host@example.com", "guest@example.com", "Guest", null, zoom));
        mockServer.verify();
    }

    // ── UPDATE ──────────────────────────────────────────────────────────────

    @Test
    void updateEvent_nullCalendarId_usesDefaultEventsPath() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events/evt-1"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("{\"id\":\"evt-1\"}", MediaType.APPLICATION_JSON));

        client.updateEvent("token", updateRequest(null, "evt-1"));
        mockServer.verify();
    }

    @Test
    void updateEvent_explicitCalendarId_usesCalendarsPath() {
        String calId = "AAMkAGI2cal";
        String encodedId = java.net.URLEncoder.encode(calId, java.nio.charset.StandardCharsets.UTF_8);
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/calendars/" + encodedId + "/events/evt-2"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("{\"id\":\"evt-2\"}", MediaType.APPLICATION_JSON));

        client.updateEvent("token", updateRequest(calId, "evt-2"));
        mockServer.verify();
    }

    // ── DELETE ──────────────────────────────────────────────────────────────

    @Test
    void deleteEvent_nullCalendarId_usesDefaultEventsPath() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events/evt-del"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        client.deleteEvent("token", null, "evt-del");
        mockServer.verify();
    }

    @Test
    void deleteEvent_primaryCalendarId_usesDefaultEventsPath() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events/evt-del2"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        client.deleteEvent("token", "primary", "evt-del2");
        mockServer.verify();
    }

    // ── INCREMENTAL SYNC URI HANDLING ───────────────────────────────────────

    @Test
    void listEventsIncremental_passesOpaqueDeltaLinkVerbatim() {
        // A real Graph @odata.nextLink contains pre-encoded reserved chars (%24, %2C, …).
        // The client must send these to Graph verbatim — never re-template them.
        String opaqueLink = "https://graph.microsoft.com/v1.0/me/calendar/events?%24select=id%2Cstart%2Cend&%24skiptoken=ZZZ";
        mockServer.expect(requestTo(opaqueLink))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

        client.listEventsIncremental("token", opaqueLink);
        mockServer.verify();
    }

    @Test
    void listEventsIncremental_rejectsDoubleEncodedOversizedCursor() {
        // 5KB cursor: definitely past the 4KB guardrail. Should fail fast (410) without HTTP call.
        String oversized = "https://graph.microsoft.com/v1.0/me/calendar/events?%24select=" + "x".repeat(5000);

        CalendarClientException ex = assertThrows(CalendarClientException.class,
                () -> client.listEventsIncremental("token", oversized));
        assertEquals(410, ex.getStatusCode());
        // No HTTP request should have been made
        mockServer.verify();
    }

    @Test
    void listEventsFull_usesStaticGraphUrl() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/calendar/events?$select=id,start,end,isCancelled,lastModifiedDateTime,changeKey"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"value\":[],\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/me/calendar/events?%24skiptoken=AAA\"}", MediaType.APPLICATION_JSON));

        var window = client.listEventsFull("token");
        assertEquals("https://graph.microsoft.com/v1.0/me/calendar/events?%24skiptoken=AAA", window.nextDeltaCursor());
        mockServer.verify();
    }

    // ── ORGANIZER MAILBOX EXTRACTION ────────────────────────────────────────

    @Test
    void createEvent_extractsOrganizerEmailFromGraphResponse() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"evt-org",
                         "organizer":{"emailAddress":{"address":"host@outlook.com","name":"Host"}}}
                        """, MediaType.APPLICATION_JSON));

        var result = client.createEvent("token", createRequest(null));

        assertEquals("evt-org", result.externalEventId());
        assertEquals("host@outlook.com", result.organizerEmail());
        mockServer.verify();
    }

    @Test
    void createEvent_missingOrganizer_returnsNullOrganizerEmail() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"evt-no-org\"}", MediaType.APPLICATION_JSON));

        var result = client.createEvent("token", createRequest(null));
        assertNull(result.organizerEmail());
        mockServer.verify();
    }

    // ── TOKEN CLASSIFICATION LOGGING ────────────────────────────────────────
    // We don't unit-test the JWT parsing branch directly (the helper is private and
    // exercised through createEvent path). The classification log is observed in
    // runtime logs; tests below assert request body is unaffected when an opaque
    // (non-JWT) token is supplied.

    @Test
    void createEvent_withOpaqueTokenString_doesNotBreakRequestPath() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"evt-opaque\"}", MediaType.APPLICATION_JSON));

        // an opaque (non-JWT) token has no dots; parser should return classification=OPAQUE_TOKEN without throwing
        client.createEvent("opaquetoken", createRequest(null));
        mockServer.verify();
    }

    // ── RESPONSE PARSING ────────────────────────────────────────────────────

    @Test
    void createEvent_missingOnlineMeeting_returnsNullConferenceUrl() {
        mockServer.expect(requestTo("https://graph.microsoft.com/v1.0/me/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"evt-noc\",\"webLink\":\"https://link\"}", MediaType.APPLICATION_JSON));

        var result = client.createEvent("token", createRequest(null));

        assertEquals("evt-noc", result.externalEventId());
        assertNull(result.conferenceUrl());
        mockServer.verify();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static CreateEventRequest createRequest(String targetCalendarId) {
        return new CreateEventRequest(
                UUID.randomUUID(), "30 min Intro", "desc",
                Instant.parse("2026-05-24T10:00:00Z"), Instant.parse("2026-05-24T10:30:00Z"),
                "host@example.com", "guest@example.com", "Guest User",
                "idem-key", targetCalendarId, ConferencingInstruction.none());
    }

    private static UpdateEventRequest updateRequest(String targetCalendarId, String externalEventId) {
        return new UpdateEventRequest(
                UUID.randomUUID(), externalEventId, "30 min Intro", "desc",
                Instant.parse("2026-05-24T10:00:00Z"), Instant.parse("2026-05-24T10:30:00Z"),
                "host@example.com", "guest@example.com", "Guest User",
                targetCalendarId, ConferencingInstruction.none());
    }
}
