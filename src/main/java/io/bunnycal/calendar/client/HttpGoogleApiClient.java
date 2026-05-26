package io.bunnycal.calendar.client;

import io.bunnycal.calendar.config.GoogleOAuthProperties;
import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component("rawGoogleApiClient")
public class HttpGoogleApiClient implements GoogleApiClient {
    private static final Logger log = LoggerFactory.getLogger(HttpGoogleApiClient.class);
    private static final String CALENDAR_ID = "primary";
    // sendUpdates=none: app is the canonical organizer and emits its own ICS invites/updates/cancels.
    // Google Calendar remains a silent time-block mirror — it must not dispatch parallel invitation emails.
    static final String CREATE_EVENT_URI_TEMPLATE = "/calendar/v3/calendars/{calendarId}/events?sendUpdates=none&conferenceDataVersion=1";
    static final String UPDATE_EVENT_URI_TEMPLATE = "/calendar/v3/calendars/{calendarId}/events/{id}?sendUpdates=none&conferenceDataVersion=1";
    // Back-compat constants retained for tests/diagnostics that reference the primary-calendar URI directly.
    static final String CREATE_EVENT_URI = "/calendar/v3/calendars/primary/events?sendUpdates=none&conferenceDataVersion=1";
    static final String UPDATE_EVENT_URI = "/calendar/v3/calendars/primary/events/{id}?sendUpdates=none&conferenceDataVersion=1";

    private final RestClient restClient;
    private final GoogleOAuthProperties googleOAuthProperties;
    private final boolean diagnosticsEnabled;
    private final MeterRegistry meterRegistry;

    public HttpGoogleApiClient(RestClient.Builder restClientBuilder,
                               GoogleOAuthProperties googleOAuthProperties,
                               MeterRegistry meterRegistry,
                               @Value("${calendar.google.diagnostics.enabled:false}") boolean diagnosticsEnabled) {
        this.restClient = restClientBuilder
                .baseUrl("https://www.googleapis.com")
                .build();
        this.googleOAuthProperties = googleOAuthProperties;
        this.meterRegistry = meterRegistry;
        this.diagnosticsEnabled = diagnosticsEnabled;
    }

    @Override
    public GoogleEventDetails createEvent(String accessToken, CreateEventRequest request) {
        try {
            Map<String, Object> body = buildCreateEventBody(request);
            Object start = body.get("start");
            Object end = body.get("end");
            log.info("google_api_create_event_payload requestId={} start={} end={} timezoneMetadataPresent={} startsAtUtc={} endsAtUtc={}",
                    request.idempotencyKey(),
                    start,
                    end,
                    hasTimezoneMetadata(start) || hasTimezoneMetadata(end),
                    request.startsAt(),
                    request.endsAt());
            ResponseEntity<Map> response = restClient.post()
                     .uri(CREATE_EVENT_URI_TEMPLATE, effectiveCalendarId(request.targetCalendarId()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            if (diagnosticsEnabled) {
                emitDiagnostics("create", accessToken, request.idempotencyKey(), CALENDAR_ID, "none", 1,
                        request.organizerEmail(), request.attendeeEmail(), body, response.getBody());
            }
            log.info("google_calendar_event_create_response requestId={} externalEventId={} conferenceLinkPresent={}",
                    request.idempotencyKey(), extractId(response.getBody()), extractConferenceLink(response.getBody()) != null);
            return toDetails(response.getBody());
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public GoogleEventDetails updateEvent(String accessToken, UpdateEventRequest request) {
        try {
            Map<String, Object> body = buildUpdateEventBody(request);
            Object start = body.get("start");
            Object end = body.get("end");
            log.info("google_api_update_event_payload externalEventId={} start={} end={} timezoneMetadataPresent={} startsAtUtc={} endsAtUtc={}",
                    request.externalEventId(),
                    start,
                    end,
                    hasTimezoneMetadata(start) || hasTimezoneMetadata(end),
                    request.startsAt(),
                    request.endsAt());
            ResponseEntity<Map> response = restClient.put()
                     .uri(UPDATE_EVENT_URI_TEMPLATE, effectiveCalendarId(request.targetCalendarId()), request.externalEventId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            if (diagnosticsEnabled) {
                emitDiagnostics("update", accessToken, request.externalEventId(), CALENDAR_ID, "none", 1,
                        request.organizerEmail(), request.attendeeEmail(), body, response.getBody());
            }
            log.info("google_calendar_event_update_response externalEventId={} conferenceLinkPresent={}",
                    extractId(response.getBody()), extractConferenceLink(response.getBody()) != null);
            return toDetails(response.getBody());
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
    public boolean eventExists(String accessToken, String externalEventId) {
        try {
            ResponseEntity<Map> response = restClient.get()
                    .uri("/calendar/v3/calendars/primary/events/{id}", externalEventId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            if (isCancelledEventBody(response.getBody())) {
                meterRegistry.counter("calendar.google.cancelled_body_detected.total").increment();
                log.info("google_event_cancelled_body_detected externalEventId={} observeResult=missing_terminal",
                        externalEventId);
                return false;
            }
            return true;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 404 || status == 410) {
                return false;
            }
            throw classify(ex);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    static boolean isCancelledEventBody(Map<?, ?> body) {
        if (body == null) {
            return false;
        }
        Object status = body.get("status");
        return status instanceof String value && "cancelled".equalsIgnoreCase(value.trim());
    }

    @Override
    public TokenRefreshResult refreshAccessToken(String refreshToken) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken);
            form.add("client_id", googleOAuthProperties.getClientId());
            form.add("client_secret", googleOAuthProperties.getClientSecret());

            ResponseEntity<Map> response = restClient.post()
                    .uri("https://oauth2.googleapis.com/token")
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

    @Override
    public SyncWindow listEventsFull(String accessToken) {
        return listEvents(accessToken, null);
    }

    @Override
    public SyncWindow listEventsIncremental(String accessToken, String syncCursor) {
        if (syncCursor == null || syncCursor.isBlank()) {
            return listEventsFull(accessToken);
        }
        return listEvents(accessToken, syncCursor);
    }

    @Override
    public WatchChannel watchEvents(String accessToken, String webhookUrl, String channelToken) {
        try {
            String channelId = UUID.randomUUID().toString();
            ResponseEntity<Map> response = restClient.post()
                    .uri("/calendar/v3/calendars/primary/events/watch")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "id", channelId,
                            "type", "web_hook",
                            "address", webhookUrl,
                            "token", channelToken
                    ))
                    .retrieve()
                    .toEntity(Map.class);
            Map body = response.getBody();
            String returnedChannelId = asStringLoose(body == null ? null : body.get("id"));
            String resourceId = asStringLoose(body == null ? null : body.get("resourceId"));
            Instant expiration = parseEpochMillisInstant(asStringLoose(body == null ? null : body.get("expiration")));
            return new WatchChannel(
                    returnedChannelId == null || returnedChannelId.isBlank() ? channelId : returnedChannelId,
                    resourceId,
                    expiration);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public void stopWatchChannel(String accessToken, String channelId, String resourceId) {
        if (channelId == null || channelId.isBlank() || resourceId == null || resourceId.isBlank()) {
            return;
        }
        try {
            restClient.post()
                    .uri("/calendar/v3/channels/stop")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "id", channelId,
                            "resourceId", resourceId
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            restClient.post()
                    .uri("https://oauth2.googleapis.com/revoke?token={token}", token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    private SyncWindow listEvents(String accessToken, String syncCursor) {
        try {
            List<CalendarEventObservation> observations = new ArrayList<>();
            String pageToken = null;
            String nextSyncToken = null;
            do {
                String currentPageToken = pageToken;
                ResponseEntity<Map> response = restClient.get()
                        .uri(uriBuilder -> {
                            uriBuilder.path("/calendar/v3/calendars/primary/events")
                                    .queryParam("showDeleted", "true")
                                    .queryParam("singleEvents", "true")
                                    .queryParam("maxResults", "2500");
                            if (syncCursor != null && !syncCursor.isBlank()) {
                                uriBuilder.queryParam("syncToken", syncCursor);
                            }
                            if (currentPageToken != null && !currentPageToken.isBlank()) {
                                uriBuilder.queryParam("pageToken", currentPageToken);
                            }
                            return uriBuilder.build();
                        })
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .toEntity(Map.class);
                Map body = response.getBody();
                observations.addAll(toObservations(body));
                pageToken = asStringLoose(body == null ? null : body.get("nextPageToken"));
                nextSyncToken = asStringLoose(body == null ? null : body.get("nextSyncToken"));
            } while (pageToken != null && !pageToken.isBlank());
            return new SyncWindow(List.copyOf(observations), nextSyncToken);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 410 && syncCursor != null && !syncCursor.isBlank()) {
                throw new CalendarClientException(410, "Sync token invalid or expired");
            }
            throw classify(ex);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    private static CalendarClientException classify(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseEx) {
            String body = responseEx.getResponseBodyAsString();
            String message = "Calendar API request failed: status=%d body=%s"
                    .formatted(responseEx.getStatusCode().value(), body == null ? "" : body);
            return new CalendarClientException(responseEx.getStatusCode().value(), message);
        }
        String message = ex.getMessage() == null ? "calendar api error" : ex.getMessage();
        int status = extractStatus(message);
        return new CalendarClientException(status, "Calendar API request failed: " + message);
    }

    private static List<CalendarEventObservation> toObservations(Map body) {
        if (body == null) {
            return List.of();
        }
        Object itemsObj = body.get("items");
        if (!(itemsObj instanceof List<?> items)) {
            return List.of();
        }
        List<CalendarEventObservation> out = new ArrayList<>();
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item)) {
                continue;
            }
            String id = asStringLoose(item.get("id"));
            if (id == null || id.isBlank()) {
                continue;
            }
            boolean cancelled = "cancelled".equalsIgnoreCase(asStringLoose(item.get("status")));
            Instant updated = parseInstant(asStringLoose(item.get("updated")));
            Instant start = parseDateTimeField(item.get("start"), updated);
            Instant end = parseDateTimeField(item.get("end"), updated == null ? null : updated.plusSeconds(60));
            if (start == null || end == null || !start.isBefore(end)) {
                if (cancelled) {
                    Instant anchor = updated == null ? Instant.EPOCH : updated;
                    start = anchor;
                    end = anchor.plusSeconds(60);
                } else if (updated != null) {
                    start = updated;
                    end = updated.plusSeconds(60);
                } else {
                    continue;
                }
            }
            String etag = asStringLoose(item.get("etag"));
            Long sequence = asLong(item.get("sequence"));
            String payloadHash = stableObservationHash(id, sequence, updated, etag, cancelled, start, end);
            out.add(new CalendarEventObservation(id, start, end, cancelled, sequence, updated, etag, payloadHash));
        }
        return out;
    }

    private static String stableObservationHash(String id,
                                                Long sequence,
                                                Instant updated,
                                                String etag,
                                                boolean cancelled,
                                                Instant start,
                                                Instant end) {
        String canonical = String.join("|",
                id == null ? "" : id,
                sequence == null ? "" : String.valueOf(sequence),
                updated == null ? "" : updated.toString(),
                etag == null ? "" : etag,
                String.valueOf(cancelled),
                start == null ? "" : start.toString(),
                end == null ? "" : end.toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(canonical.hashCode());
        }
    }

    private static Instant parseDateTimeField(Object value, Instant fallback) {
        if (!(value instanceof Map<?, ?> map)) {
            return fallback;
        }
        String dt = asStringLoose(map.get("dateTime"));
        if (dt != null) {
            Instant parsed = parseInstant(dt);
            if (parsed != null) {
                return parsed;
            }
        }
        String date = asStringLoose(map.get("date"));
        if (date != null) {
            try {
                return Instant.parse(date + "T00:00:00Z");
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String effectiveCalendarId(String calendarId) {
        if (calendarId == null || calendarId.isBlank()) {
            return CALENDAR_ID;
        }
        return calendarId.trim();
    }

    private static String asStringLoose(Object value) {
        if (value instanceof String s) {
            return s;
        }
        return value == null ? null : String.valueOf(value);
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

    private static Instant parseEpochMillisInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long epochMillis = Long.parseLong(value);
            return Instant.ofEpochMilli(epochMillis);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static Map<String, Object> buildCreateEventBody(CreateEventRequest request) {
        Map<String, Object> body = new HashMap<>();
        ConferencingInstruction instruction = instructionOrNone(request.conferencingInstruction());
        body.put("summary", request.title());
        body.put("description", appendConferenceUrl(request.description(), instruction));
        body.put("start", Map.of("dateTime", request.startsAt().toString()));
        body.put("end", Map.of("dateTime", request.endsAt().toString()));
        body.put("extendedProperties", Map.of("private", Map.of("idempotencyKey", request.idempotencyKey())));
        if (instruction.requestsNativeMeet()
                && instruction.providerType() == ConferencingProviderType.GOOGLE_MEET) {
            body.put("conferenceData", Map.of(
                    "createRequest", Map.of(
                            "requestId", request.idempotencyKey(),
                            "conferenceSolutionKey", Map.of("type", "hangoutsMeet")
                    )));
        }
        if (instruction.embedsExternalUrl()) {
            body.put("location", instruction.joinUrl());
        }
        body.put("attendees", attendees(request.attendeeEmail(), request.attendeeName()));
        return body;
    }

    static Map<String, Object> buildUpdateEventBody(UpdateEventRequest request) {
        Map<String, Object> body = new HashMap<>();
        ConferencingInstruction instruction = instructionOrNone(request.conferencingInstruction());
        body.put("summary", request.title());
        body.put("description", appendConferenceUrl(request.description(), instruction));
        body.put("start", Map.of("dateTime", request.startsAt().toString()));
        body.put("end", Map.of("dateTime", request.endsAt().toString()));
        if (instruction.requestsNativeMeet()
                && instruction.providerType() == ConferencingProviderType.GOOGLE_MEET) {
            body.put("conferenceData", Map.of(
                    "createRequest", Map.of(
                            "requestId", request.externalEventId(),
                            "conferenceSolutionKey", Map.of("type", "hangoutsMeet")
                    )));
        }
        if (instruction.embedsExternalUrl()) {
            body.put("location", instruction.joinUrl());
        }
        body.put("attendees", attendees(request.attendeeEmail(), request.attendeeName()));
        return body;
    }

    private static ConferencingInstruction instructionOrNone(ConferencingInstruction instruction) {
        return instruction == null ? ConferencingInstruction.none() : instruction;
    }

    private static String appendConferenceUrl(String description, ConferencingInstruction instruction) {
        String base = description == null ? "" : description.trim();
        if (!instruction.embedsExternalUrl()) {
            return base;
        }
        String joinLine = "conferenceUrl=" + instruction.joinUrl();
        if (base.isBlank()) {
            return joinLine;
        }
        if (base.contains(joinLine)) {
            return base;
        }
        return base + "\n" + joinLine;
    }

    static List<Map<String, Object>> attendees(String attendeeEmail, String attendeeName) {
        List<Map<String, Object>> attendees = new ArrayList<>();
        if (attendeeEmail != null && !attendeeEmail.isBlank()) {
            Map<String, Object> guest = new HashMap<>();
            guest.put("email", attendeeEmail);
            if (attendeeName != null && !attendeeName.isBlank()) {
                guest.put("displayName", attendeeName);
            }
            attendees.add(guest);
        }
        return attendees;
    }

    private static String extractId(Map body) {
        if (body == null) return null;
        Object id = body.get("id");
        return id instanceof String s ? s : null;
    }

    private static String extractConferenceLink(Map body) {
        if (body == null) return null;
        Object hangout = body.get("hangoutLink");
        if (hangout instanceof String s && !s.isBlank()) {
            return s;
        }
        Object conferenceDataObj = body.get("conferenceData");
        if (!(conferenceDataObj instanceof Map<?, ?> conferenceData)) {
            return null;
        }
        Object entryPointsObj = conferenceData.get("entryPoints");
        if (!(entryPointsObj instanceof List<?> entryPoints)) {
            return null;
        }
        for (Object entry : entryPoints) {
            if (!(entry instanceof Map<?, ?> point)) {
                continue;
            }
            Object uri = point.get("uri");
            if (uri instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static GoogleEventDetails toDetails(Map body) {
        return new GoogleEventDetails(extractId(body), extractHtmlLink(body), extractConferenceLink(body));
    }

    private static boolean hasTimezoneMetadata(Object timeObj) {
        if (!(timeObj instanceof Map<?, ?> map)) {
            return false;
        }
        return map.containsKey("timeZone");
    }

    private static String extractHtmlLink(Map body) {
        if (body == null) return null;
        Object htmlLink = body.get("htmlLink");
        return htmlLink instanceof String s ? s : null;
    }

    private void emitDiagnostics(String action,
                                 String accessToken,
                                 String correlationId,
                                 String calendarId,
                                 String sendUpdates,
                                 int conferenceDataVersion,
                                 String expectedOrganizerEmail,
                                 String expectedAttendeeEmail,
                                 Map<String, Object> requestBody,
                                 Map<String, Object> responseBody) {
        Map<String, Object> userInfo = fetchUserInfoSafe(accessToken);
        String eventId = extractId(responseBody);
        Map<String, Object> fetched = fetchEventSafe(accessToken, calendarId, eventId);
        Map<String, Object> source = fetched != null ? fetched : responseBody;
        String oauthEmail = asString(userInfo == null ? null : userInfo.get("email"));
        String organizerEmail = nestedString(source, "organizer", "email");
        boolean attendeePresent = attendeePresent(source, expectedAttendeeEmail);
        String warningCode = diagnoseWarning(
                fetched,
                source,
                expectedOrganizerEmail,
                organizerEmail,
                oauthEmail,
                expectedAttendeeEmail,
                attendeePresent
        );
        log.info(
                "google_calendar_provider_truth action={} correlationId={} calendarId={} targetIsPrimary={} sendUpdates={} conferenceDataVersion={} oauthSub={} oauthEmail={} responseEventId={} responseHtmlLink={} responseStatus={} organizerEmail={} creatorEmail={} attendees={} expectedOrganizerEmail={} expectedAttendeeEmail={} attendeePresent={} warningCode={} hangoutLink={} conferenceData={} visibility={} summary={} start={} end={} fetchedFromGoogle={}",
                action,
                correlationId,
                calendarId,
                "primary".equals(calendarId),
                sendUpdates,
                conferenceDataVersion,
                asString(userInfo == null ? null : userInfo.get("sub")),
                maskEmail(oauthEmail),
                eventId,
                extractHtmlLink(source),
                asString(source == null ? null : source.get("status")),
                organizerEmail,
                nestedString(source, "creator", "email"),
                attendeeSummary(source),
                expectedOrganizerEmail,
                expectedAttendeeEmail,
                attendeePresent,
                warningCode,
                extractConferenceLink(source),
                source == null ? null : source.get("conferenceData"),
                asString(source == null ? null : source.get("visibility")),
                asString(source == null ? null : source.get("summary")),
                source == null ? null : source.get("start"),
                source == null ? null : source.get("end"),
                fetched != null);

        log.info("google_calendar_provider_request action={} correlationId={} attendeeCount={} attendeeEmails={} requestBodyKeys={}",
                action,
                correlationId,
                attendeeCountFromRequest(requestBody),
                attendeeEmailsFromRequest(requestBody),
                requestBody == null ? List.of() : requestBody.keySet());
        if (!"NONE".equals(warningCode)) {
            log.warn("google_calendar_provider_truth_warning action={} correlationId={} warningCode={} calendarId={} eventId={}",
                    action, correlationId, warningCode, calendarId, eventId);
        }
    }

    private Map<String, Object> fetchUserInfoSafe(String accessToken) {
        try {
            ResponseEntity<Map> response = restClient.get()
                    .uri("https://www.googleapis.com/oauth2/v3/userinfo")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            return response.getBody();
        } catch (RuntimeException ex) {
            log.warn("google_calendar_provider_truth_userinfo_failed message={}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> fetchEventSafe(String accessToken, String calendarId, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        try {
            ResponseEntity<Map> response = restClient.get()
                    .uri("/calendar/v3/calendars/{calendarId}/events/{id}", calendarId, eventId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            return response.getBody();
        } catch (RuntimeException ex) {
            log.warn("google_calendar_provider_truth_event_fetch_failed calendarId={} eventId={} message={}",
                    calendarId, eventId, ex.getMessage());
            return null;
        }
    }

    private static String nestedString(Map body, String parentKey, String childKey) {
        if (body == null) return null;
        Object parent = body.get(parentKey);
        if (!(parent instanceof Map<?, ?> parentMap)) {
            return null;
        }
        Object child = parentMap.get(childKey);
        return child instanceof String s ? s : null;
    }

    private static String attendeeSummary(Map body) {
        if (body == null) return "[]";
        Object attendeesObj = body.get("attendees");
        if (!(attendeesObj instanceof List<?> attendees)) {
            return "[]";
        }
        List<String> summary = new ArrayList<>();
        for (Object attendee : attendees) {
            if (!(attendee instanceof Map<?, ?> a)) {
                continue;
            }
            String email = asString(a.get("email"));
            String status = asString(a.get("responseStatus"));
            summary.add(maskEmail(email) + ":" + status);
        }
        return summary.toString();
    }

    private static boolean attendeePresent(Map source, String expectedAttendeeEmail) {
        if (source == null || expectedAttendeeEmail == null || expectedAttendeeEmail.isBlank()) {
            return false;
        }
        Object attendeesObj = source.get("attendees");
        if (!(attendeesObj instanceof List<?> attendees)) {
            return false;
        }
        for (Object attendee : attendees) {
            if (!(attendee instanceof Map<?, ?> a)) {
                continue;
            }
            String email = asString(a.get("email"));
            if (email != null && email.equalsIgnoreCase(expectedAttendeeEmail)) {
                return true;
            }
        }
        return false;
    }

    private static int attendeeCountFromRequest(Map<String, Object> body) {
        if (body == null) return 0;
        Object attendeesObj = body.get("attendees");
        if (!(attendeesObj instanceof List<?> attendees)) {
            return 0;
        }
        return attendees.size();
    }

    private static String attendeeEmailsFromRequest(Map<String, Object> body) {
        if (body == null) return "[]";
        Object attendeesObj = body.get("attendees");
        if (!(attendeesObj instanceof List<?> attendees)) {
            return "[]";
        }
        List<String> emails = new ArrayList<>();
        for (Object attendee : attendees) {
            if (!(attendee instanceof Map<?, ?> a)) continue;
            emails.add(maskEmail(asString(a.get("email"))));
        }
        return emails.toString();
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    static String diagnoseWarning(Map<String, Object> fetched,
                                  Map<String, Object> source,
                                  String expectedOrganizerEmail,
                                  String organizerEmail,
                                  String oauthEmail,
                                  String expectedAttendeeEmail,
                                  boolean attendeePresent) {
        if (fetched == null) {
            return "EVENT_NOT_FOUND_ON_READBACK";
        }
        if (source == null) {
            return "MISSING_SOURCE";
        }
        String status = asString(source.get("status"));
        if (status != null && !"confirmed".equalsIgnoreCase(status)) {
            return "EVENT_NOT_CONFIRMED";
        }
        if (expectedOrganizerEmail != null && organizerEmail != null
                && !expectedOrganizerEmail.equalsIgnoreCase(organizerEmail)) {
            return "ORGANIZER_EMAIL_MISMATCH";
        }
        if (oauthEmail != null && expectedOrganizerEmail != null
                && !oauthEmail.equalsIgnoreCase(expectedOrganizerEmail)) {
            return "OAUTH_ACCOUNT_MISMATCH";
        }
        if (expectedAttendeeEmail != null && !expectedAttendeeEmail.isBlank() && !attendeePresent) {
            return "GUEST_ATTENDEE_MISSING";
        }
        if (extractHtmlLink(source) == null || extractHtmlLink(source).isBlank()) {
            return "EVENT_HTML_LINK_MISSING";
        }
        return "NONE";
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

}
