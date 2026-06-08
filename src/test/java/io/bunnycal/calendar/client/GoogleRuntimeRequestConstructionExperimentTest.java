package io.bunnycal.calendar.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.bunnycal.calendar.config.GoogleOAuthProperties;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

class GoogleRuntimeRequestConstructionExperimentTest {

    private static final String EMPTY_EVENTS_PAGE = """
            {"items":[],"nextSyncToken":"tok-1"}
            """;

    @Test
    void httpGoogleApiClient_runtime_transmitted_uri_observation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpGoogleApiClient client = new HttpGoogleApiClient(
                builder,
                stubGoogleProps(),
                new SimpleMeterRegistry(),
                false);

        // Register all expectations before the first request (SimpleRequestExpectationManager requirement).
        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/koushikruidas%40gmail.com/events?showDeleted=true&singleEvents=true&maxResults=2500"))
                .andExpect(method(GET))
                .andRespond(withSuccess(EMPTY_EVENTS_PAGE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/koushikruidas%40gmail.com/events?showDeleted=true&singleEvents=true&maxResults=2500"))
                .andExpect(method(GET))
                .andRespond(withSuccess(EMPTY_EVENTS_PAGE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/family17130278116817796873%40group.calendar.google.com/events?showDeleted=true&singleEvents=true&maxResults=2500"))
                .andExpect(method(GET))
                .andRespond(withSuccess(EMPTY_EVENTS_PAGE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/en.indian%23holiday%40group.v.calendar.google.com/events?showDeleted=true&singleEvents=true&maxResults=2500"))
                .andExpect(method(GET))
                .andRespond(withSuccess(EMPTY_EVENTS_PAGE, MediaType.APPLICATION_JSON));

        // Runtime should now encode exactly once at the framework layer.
        client.listEventsFull("token", "koushikruidas@gmail.com");
        // Already-encoded input is first normalized to decoded raw id, then encoded once by RestClient.
        client.listEventsFull("token", "koushikruidas%40gmail.com");
        client.listEventsFull("token", "family17130278116817796873@group.calendar.google.com");
        client.listEventsFull("token", "en.indian#holiday@group.v.calendar.google.com");

        server.verify();
    }

    @Test
    void restClient_runtime_path_builder_comparison_encoded_vs_raw_segments() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://www.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        List<String> rawPaths = List.of(
                "/calendar/v3/calendars/koushikruidas@gmail.com/events?maxResults=1",
                "/calendar/v3/calendars/family17130278116817796873@group.calendar.google.com/events?maxResults=1",
                "/calendar/v3/calendars/en.indian#holiday@group.v.calendar.google.com/events?maxResults=1"
        );
        List<String> encodedPaths = List.of(
                "/calendar/v3/calendars/koushikruidas%40gmail.com/events?maxResults=1",
                "/calendar/v3/calendars/family17130278116817796873%40group.calendar.google.com/events?maxResults=1",
                "/calendar/v3/calendars/en.indian%23holiday%40group.v.calendar.google.com/events?maxResults=1"
        );

        // Register expectations first.
        // Directly passing a pre-encoded path string to RestClient still re-encodes '%' -> %25.
        for (String path : encodedPaths) {
            String expected = "https://www.googleapis.com" + path
                    .replace("%40", "%2540")
                    .replace("%23", "%2523");
            server.expect(requestTo(expected))
                    .andExpect(method(GET))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo("https://www.googleapis.com" + rawPaths.get(0)))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com" + rawPaths.get(1)))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/en.indian#holiday@group.v.calendar.google.com/events?maxResults=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // Execute encoded then raw variants.
        for (String path : encodedPaths) {
            restClient.get().uri(path).retrieve().toBodilessEntity();
        }
        restClient.get().uri(rawPaths.get(0)).retrieve().toBodilessEntity();
        restClient.get().uri(rawPaths.get(1)).retrieve().toBodilessEntity();
        // Raw string variant preserves the literal '#' in the URI string form used by RestClient.
        restClient.get().uri(rawPaths.get(2)).retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void normalization_keeps_canonicalization_without_manual_encoding_helper() {
        assertThat(HttpGoogleApiClient.normalizeGoogleCalendarId("[koushikruidas@gmail.com](mailto:koushikruidas@gmail.com)"))
                .isEqualTo("koushikruidas@gmail.com");
        assertThat(HttpGoogleApiClient.normalizeGoogleCalendarId("mailto:koushikruidas@gmail.com"))
                .isEqualTo("koushikruidas@gmail.com");
        assertThat(HttpGoogleApiClient.normalizeGoogleCalendarId("koushikruidas@gmail.com"))
                .isEqualTo("koushikruidas@gmail.com");
    }

    @Test
    void updateEvent_usesPatchSoExistingConferenceStateIsNotFullyReplaced() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpGoogleApiClient client = new HttpGoogleApiClient(
                builder,
                stubGoogleProps(),
                new SimpleMeterRegistry(),
                false);

        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/primary/events/ext-1?sendUpdates=none&conferenceDataVersion=1"))
                .andExpect(method(PATCH))
                .andRespond(withSuccess("""
                        {
                          "id":"ext-1",
                          "htmlLink":"https://calendar.google.com/event?eid=1",
                          "hangoutLink":"https://meet.google.com/existing-link"
                        }
                        """, MediaType.APPLICATION_JSON));

        client.updateEvent("token", UpdateEventRequest.forGroup(
                UUID.randomUUID(),
                "ext-1",
                "Group Session",
                "sessionId=1",
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T10:00:00Z"),
                "host@example.com",
                List.of(),
                "primary",
                ConferencingInstruction.none()));

        server.verify();
    }

    private static GoogleOAuthProperties stubGoogleProps() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setFrontendBaseUrl("https://example.com");
        props.setFrontendSuccessPath("/ok");
        props.setFrontendErrorPath("/err");
        props.setClientId("dummy");
        props.setClientSecret("dummy");
        props.setRedirectUri("https://example.com/callback");
        props.setScopes(List.of(
                "https://www.googleapis.com/auth/calendar.readonly",
                "openid",
                "email",
                "profile"
        ));
        return props;
    }
}
