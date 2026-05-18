package com.daedalussystems.easySchedule.conferencing.client;

import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpZoomApiClient implements ZoomApiClient {
    private final RestClient restClient;

    public HttpZoomApiClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl("https://api.zoom.us").build();
    }

    @Override
    public OAuthTokenExchangeResult exchangeCodeForToken(String code, String redirectUri, String clientId, String clientSecret) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", redirectUri);
            ResponseEntity<Map> response = restClient.post()
                    .uri("https://zoom.us/oauth/token")
                    .headers(h -> h.setBasicAuth(clientId, clientSecret))
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
    public TokenRefreshResult refreshAccessToken(String refreshToken, String clientId, String clientSecret) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken);
            ResponseEntity<Map> response = restClient.post()
                    .uri("https://zoom.us/oauth/token")
                    .headers(h -> h.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
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
    public void revokeToken(String token) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("token", token);
            restClient.post().uri("https://zoom.us/oauth/revoke")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public String fetchProviderUserId(String accessToken) {
        try {
            ResponseEntity<Map> response = restClient.get()
                    .uri("/v2/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve().toEntity(Map.class);
            Object id = response.getBody().get("id");
            return id == null ? null : id.toString();
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public MeetingDetails createMeeting(String accessToken, String topic, Instant start, Instant end) {
        try {
            ResponseEntity<Map> response = restClient.post()
                    .uri("/v2/users/me/meetings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "topic", topic,
                            "type", 2,
                            "start_time", DateTimeFormatter.ISO_INSTANT.format(start.atOffset(ZoneOffset.UTC)),
                            "duration", Math.max(1, (int) java.time.Duration.between(start, end).toMinutes())
                    ))
                    .retrieve().toEntity(Map.class);
            return mapMeeting(response.getBody());
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public MeetingDetails updateMeeting(String accessToken, String meetingId, String topic, Instant start, Instant end) {
        try {
            restClient.patch().uri("/v2/meetings/{id}", meetingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "topic", topic,
                            "start_time", DateTimeFormatter.ISO_INSTANT.format(start.atOffset(ZoneOffset.UTC)),
                            "duration", Math.max(1, (int) java.time.Duration.between(start, end).toMinutes())
                    ))
                    .retrieve().toBodilessEntity();
            return new MeetingDetails(meetingId, null, null);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public void deleteMeeting(String accessToken, String meetingId) {
        try {
            restClient.delete().uri("/v2/meetings/{id}", meetingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    private static MeetingDetails mapMeeting(Map body) {
        String id = body.get("id") == null ? null : body.get("id").toString();
        return new MeetingDetails(id, (String) body.get("join_url"), (String) body.get("start_url"));
    }

    private static CalendarClientException classify(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseEx) {
            return new CalendarClientException(responseEx.getStatusCode().value(), responseEx.getResponseBodyAsString());
        }
        return new CalendarClientException(503, "zoom client call failed");
    }
}
