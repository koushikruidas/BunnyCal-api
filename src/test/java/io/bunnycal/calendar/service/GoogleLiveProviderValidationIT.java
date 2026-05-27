package io.bunnycal.calendar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.TestApplication;
import io.bunnycal.calendar.auth.TokenRefresher;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = TestApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/mydatabase",
        "spring.datasource.username=myuser",
        "spring.datasource.password=secret",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.docker.compose.enabled=false",
        "spring.task.scheduling.enabled=false",
        "security.enabled=false",
        "spring.otel.sdk.disabled=true"
})
class GoogleLiveProviderValidationIT {

    private static final UUID CONNECTION_ID = UUID.fromString("6d0541a8-e112-453b-81bc-8aaa25605b9f");
    private static final List<String> CALENDAR_IDS = List.of(
            "primary",
            "koushikruidas@gmail.com",
            "[koushikruidas@gmail.com](mailto:koushikruidas@gmail.com)",
            "family17130278116817796873@group.calendar.google.com",
            "en.indian#holiday@group.v.calendar.google.com"
    );

    @Autowired private TokenRefresher tokenRefresher;
    @Autowired private CalendarConnectionRepository connectionRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Test
    void validateLiveGoogleCalendarsWithStoredBackendToken() {
        CalendarConnection connection = connectionRepository.findById(CONNECTION_ID)
                .orElseThrow(() -> new IllegalStateException("Connection not found: " + CONNECTION_ID));

        tokenRefresher.executeWithValidToken(CONNECTION_ID, accessToken -> {
            emitTokenIdentity(connection, accessToken);
            for (String calendarId : CALENDAR_IDS) {
                validateCalendar(connection, accessToken, calendarId);
            }
            return null;
        });
    }

    private void emitTokenIdentity(CalendarConnection connection, String accessToken) {
        try {
            ApiResult userInfo = get("https://www.googleapis.com/oauth2/v3/userinfo", accessToken);
            ApiResult tokenInfo = get("https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=" + enc(accessToken), accessToken);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "google_token_validation");
            payload.put("connectionId", connection.getId().toString());
            payload.put("oauthEmail", readField(userInfo.body, "email"));
            payload.put("oauthSub", readField(userInfo.body, "sub"));
            payload.put("verifiedEmail", readField(userInfo.body, "email_verified"));
            payload.put("scopes", readField(tokenInfo.body, "scope"));
            payload.put("tokenAudience", readField(tokenInfo.body, "aud"));
            payload.put("userinfoStatus", userInfo.status);
            payload.put("tokeninfoStatus", tokenInfo.status);
            System.out.println(mapper.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new RuntimeException("Failed token identity validation", ex);
        }
    }

    private void validateCalendar(CalendarConnection connection, String accessToken, String calendarIdRaw) {
        try {
            String encoded = URLEncoder.encode(calendarIdRaw, StandardCharsets.UTF_8);
            String metadataUrl = "https://www.googleapis.com/calendar/v3/calendars/" + encoded;
            String eventsUrl = metadataUrl + "/events?maxResults=1";

            ApiResult metadata = get(metadataUrl, accessToken);
            ApiResult events = get(eventsUrl, accessToken);

            Map<String, Object> metadataLog = new LinkedHashMap<>();
            metadataLog.put("event", "google_calendar_validation");
            metadataLog.put("connectionId", connection.getId().toString());
            metadataLog.put("endpoint", "metadata");
            metadataLog.put("calendarId", calendarIdRaw);
            metadataLog.put("requestUrl", metadataUrl);
            metadataLog.put("status", metadata.status);
            metadataLog.put("success", metadata.status >= 200 && metadata.status < 300);
            metadataLog.put("responseSummary", summarize(metadata.body));
            metadataLog.put("errorBody", metadata.errorBody);
            System.out.println(mapper.writeValueAsString(metadataLog));

            Map<String, Object> eventsLog = new LinkedHashMap<>();
            eventsLog.put("event", "google_calendar_validation");
            eventsLog.put("connectionId", connection.getId().toString());
            eventsLog.put("endpoint", "events");
            eventsLog.put("calendarId", calendarIdRaw);
            eventsLog.put("requestUrl", eventsUrl);
            eventsLog.put("status", events.status);
            eventsLog.put("success", events.status >= 200 && events.status < 300);
            eventsLog.put("responseSummary", summarize(events.body));
            eventsLog.put("errorBody", events.errorBody);
            System.out.println(mapper.writeValueAsString(eventsLog));

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("event", "google_calendar_validation_summary");
            summary.put("connectionId", connection.getId().toString());
            summary.put("calendarId", calendarIdRaw);
            summary.put("metadataAccessible", metadata.status >= 200 && metadata.status < 300);
            summary.put("eventsAccessible", events.status >= 200 && events.status < 300);
            summary.put("statusCode", events.status);
            summary.put("errorReason", extractErrorReason(events.errorBody));
            summary.put("pollable", events.status >= 200 && events.status < 300);
            summary.put("selectedForAvailability", "koushikruidas@gmail.com".equals(calendarIdRaw));
            summary.put("selectedForProjection", false);
            System.out.println(mapper.writeValueAsString(summary));
        } catch (Exception ex) {
            throw new RuntimeException("Failed calendar validation for " + calendarIdRaw, ex);
        }
    }

    private ApiResult get(String url, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return new ApiResult(response.statusCode(), body, null);
        }
        return new ApiResult(response.statusCode(), null, body);
    }

    private static String summarize(String body) {
        if (body == null || body.isBlank()) return null;
        return body.length() > 240 ? body.substring(0, 240) + "..." : body;
    }

    private static String readField(String body, String field) {
        if (body == null || body.isBlank()) return null;
        String marker = "\"" + field + "\":";
        int i = body.indexOf(marker);
        if (i < 0) return null;
        int start = i + marker.length();
        int end = Math.min(body.length(), start + 200);
        return body.substring(start, end).trim();
    }

    private static String extractErrorReason(String errorBody) {
        if (errorBody == null) return null;
        if (errorBody.contains("\"reason\":")) {
            int idx = errorBody.indexOf("\"reason\":");
            int from = Math.max(0, idx);
            int to = Math.min(errorBody.length(), idx + 120);
            return errorBody.substring(from, to);
        }
        return null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record ApiResult(int status, String body, String errorBody) {}
}
