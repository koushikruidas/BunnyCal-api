package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component("rawGoogleApiClient")
public class HttpGoogleApiClient implements GoogleApiClient {
    private final RestClient restClient;

    public HttpGoogleApiClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("https://www.googleapis.com")
                .build();
    }

    @Override
    public String createEvent(String accessToken, CreateEventRequest request) {
        try {
            ResponseEntity<Map> response = restClient.post()
                    .uri("/calendar/v3/calendars/primary/events")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "summary", request.title(),
                            "description", request.description(),
                            "start", Map.of("dateTime", request.startsAt().toString()),
                            "end", Map.of("dateTime", request.endsAt().toString()),
                            "extendedProperties", Map.of("private", Map.of("idempotencyKey", request.idempotencyKey()))
                    ))
                    .retrieve()
                    .toEntity(Map.class);
            return (String) response.getBody().get("id");
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public String updateEvent(String accessToken, UpdateEventRequest request) {
        try {
            ResponseEntity<Map> response = restClient.put()
                    .uri("/calendar/v3/calendars/primary/events/{id}", request.externalEventId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "summary", request.title(),
                            "description", request.description(),
                            "start", Map.of("dateTime", request.startsAt().toString()),
                            "end", Map.of("dateTime", request.endsAt().toString())
                    ))
                    .retrieve()
                    .toEntity(Map.class);
            return (String) response.getBody().get("id");
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public void deleteEvent(String accessToken, String externalEventId) {
        try {
            restClient.delete()
                    .uri("/calendar/v3/calendars/primary/events/{id}", externalEventId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public TokenRefreshResult refreshAccessToken(String refreshToken) {
        try {
            ResponseEntity<Map> response = restClient.post()
                    .uri("https://oauth2.googleapis.com/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=refresh_token&refresh_token=" + refreshToken)
                    .retrieve()
                    .toEntity(Map.class);
            String accessToken = (String) response.getBody().get("access_token");
            Number expiresIn = (Number) response.getBody().getOrDefault("expires_in", 3600);
            return new TokenRefreshResult(accessToken, Instant.now().plusSeconds(expiresIn.longValue()));
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public OAuthTokenExchangeResult exchangeCodeForToken(String code,
                                                         String redirectUri,
                                                         String clientId,
                                                         String clientSecret) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("code", code);
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("redirect_uri", redirectUri);
            form.add("grant_type", "authorization_code");

            ResponseEntity<Map> response = restClient.post()
                    .uri("https://oauth2.googleapis.com/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toEntity(Map.class);
            String accessToken = (String) response.getBody().get("access_token");
            String refreshToken = (String) response.getBody().get("refresh_token");
            Number expiresIn = (Number) response.getBody().getOrDefault("expires_in", 3600);
            return new OAuthTokenExchangeResult(accessToken, refreshToken, Instant.now().plusSeconds(expiresIn.longValue()));
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public String fetchProviderUserId(String accessToken) {
        try {
            ResponseEntity<Map> response = restClient.get()
                    .uri("https://www.googleapis.com/oauth2/v3/userinfo")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            return (String) response.getBody().get("sub");
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public List<BusyInterval> fetchBusyIntervals(String accessToken, Instant start, Instant end) {
        try {
            ResponseEntity<Map> response = restClient.post()
                    .uri("/calendar/v3/freeBusy")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "timeMin", start.toString(),
                            "timeMax", end.toString(),
                            "items", List.of(Map.of("id", "primary"))
                    ))
                    .retrieve()
                    .toEntity(Map.class);

            Object calendarsObj = response.getBody().get("calendars");
            if (!(calendarsObj instanceof Map<?, ?> calendars)) {
                return List.of();
            }
            Object primaryObj = calendars.get("primary");
            if (!(primaryObj instanceof Map<?, ?> primary)) {
                return List.of();
            }
            Object busyObj = primary.get("busy");
            if (!(busyObj instanceof List<?> busyList)) {
                return List.of();
            }
            List<BusyInterval> intervals = new ArrayList<>();
            for (Object item : busyList) {
                if (!(item instanceof Map<?, ?> busyMap)) {
                    continue;
                }
                Object s = busyMap.get("start");
                Object e = busyMap.get("end");
                if (s instanceof String ss && e instanceof String ee) {
                    Instant si = Instant.parse(ss);
                    Instant ei = Instant.parse(ee);
                    if (si.isBefore(ei)) {
                        intervals.add(new BusyInterval(si, ei));
                    }
                }
            }
            return List.copyOf(intervals);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    private static CalendarClientException classify(RestClientException ex) {
        String message = ex.getMessage() == null ? "calendar api error" : ex.getMessage();
        int status = extractStatus(message);
        return new CalendarClientException(status, "Calendar API request failed");
    }

    private static int extractStatus(String msg) {
        if (msg.contains("401")) return 401;
        if (msg.contains("429")) return 429;
        if (msg.contains("500")) return 500;
        if (msg.contains("502")) return 502;
        if (msg.contains("503")) return 503;
        if (msg.contains("504")) return 504;
        if (msg.contains("400")) return 400;
        if (msg.contains("403")) return 403;
        if (msg.contains("404")) return 404;
        return 500;
    }

}
