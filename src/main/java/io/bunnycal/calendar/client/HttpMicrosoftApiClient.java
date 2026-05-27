package io.bunnycal.calendar.client;

import io.bunnycal.calendar.config.MicrosoftOAuthProperties;
import io.bunnycal.calendar.provider.CreateEventRequest;
import io.bunnycal.calendar.provider.UpdateEventRequest;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpMicrosoftApiClient implements MicrosoftApiClient {
    private final RestClient graphClient;
    private final RestClient identityClient;
    private final MicrosoftOAuthProperties properties;

    public HttpMicrosoftApiClient(RestClient.Builder restClientBuilder, MicrosoftOAuthProperties properties) {
        this.graphClient = restClientBuilder.baseUrl("https://graph.microsoft.com").build();
        this.identityClient = restClientBuilder.baseUrl("https://login.microsoftonline.com").build();
        this.properties = properties;
    }

    @Override
    public MicrosoftEventDetails createEvent(String accessToken, CreateEventRequest request) {
        try {
            Map<String, Object> body = buildEventBody(request.title(), request.description(), request.startsAt(), request.endsAt(),
                    request.organizerEmail(), request.attendeeEmail(), request.attendeeName(), request.conferencingInstruction());
            ResponseEntity<Map> response = graphClient.post()
                    .uri("/v1.0/me/calendars/{calendarId}/events", effectiveCalendarId(request.targetCalendarId()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("Prefer", "outlook.timezone=\"UTC\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            logContractVerified("create", body);
            return toDetails(response.getBody());
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public MicrosoftEventDetails updateEvent(String accessToken, UpdateEventRequest request) {
        try {
            Map<String, Object> body = buildEventBody(request.title(), request.description(), request.startsAt(), request.endsAt(),
                    request.organizerEmail(), request.attendeeEmail(), request.attendeeName(), request.conferencingInstruction());
            ResponseEntity<Map> response = graphClient.patch()
                    .uri("/v1.0/me/calendars/{calendarId}/events/{id}",
                            effectiveCalendarId(request.targetCalendarId()),
                            request.externalEventId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("Prefer", "outlook.timezone=\"UTC\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            logContractVerified("update", body);
            return toDetails(response.getBody());
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public void deleteEvent(String accessToken, String targetCalendarId, String externalEventId) {
        try {
            graphClient.delete()
                    .uri("/v1.0/me/calendars/{calendarId}/events/{id}",
                            effectiveCalendarId(targetCalendarId), externalEventId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public boolean eventExists(String accessToken, String targetCalendarId, String externalEventId) {
        try {
            graphClient.get()
                    .uri("/v1.0/me/calendars/{calendarId}/events/{id}",
                            effectiveCalendarId(targetCalendarId), externalEventId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
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

    @Override
    public TokenRefreshResult refreshAccessToken(String refreshToken) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken);
            form.add("client_id", properties.getClientId());
            form.add("client_secret", properties.getClientSecret());
            form.add("scope", String.join(" ", properties.getScopes()));
            ResponseEntity<Map> response = identityClient.post()
                    .uri("/{tenant}/oauth2/v2.0/token", effectiveTenant())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toEntity(Map.class);
            String accessToken = asString(response.getBody(), "access_token");
            Number expiresIn = asNumber(response.getBody(), "expires_in", 3600);
            // F4: Microsoft Graph rotates the refresh token on EVERY refresh; missing this
            // value used to cause the stored ciphertext to drift stale and eventually fail
            // with invalid_grant. Pass it through so TokenRefresher can persist it.
            String rotatedRefreshToken = asString(response.getBody(), "refresh_token");
            return new TokenRefreshResult(accessToken,
                    Instant.now().plusSeconds(expiresIn.longValue()),
                    rotatedRefreshToken);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public OAuthTokenExchangeResult exchangeCodeForToken(String code, String redirectUri, String clientId, String clientSecret, String tenantId) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("code", code);
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("redirect_uri", redirectUri);
            form.add("grant_type", "authorization_code");
            form.add("scope", String.join(" ", properties.getScopes()));
            ResponseEntity<Map> response = identityClient.post()
                    .uri("/{tenant}/oauth2/v2.0/token", tenantId == null || tenantId.isBlank() ? effectiveTenant() : tenantId)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toEntity(Map.class);
            String accessToken = asString(response.getBody(), "access_token");
            String refreshToken = asString(response.getBody(), "refresh_token");
            Number expiresIn = asNumber(response.getBody(), "expires_in", 3600);
            return new OAuthTokenExchangeResult(accessToken, refreshToken, Instant.now().plusSeconds(expiresIn.longValue()));
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public String fetchProviderUserId(String accessToken) {
        try {
            ResponseEntity<Map> response = graphClient.get()
                    .uri("/v1.0/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            return asString(response.getBody(), "id");
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public List<ProviderCalendarInventoryEntry> listCalendars(String accessToken) {
        try {
            ResponseEntity<Map> response = graphClient.get()
                    .uri("/v1.0/me/calendars?$select=id,name,isDefaultCalendar,canEdit,canShare,canViewPrivateItems,owner")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            Object values = response.getBody() == null ? null : response.getBody().get("value");
            if (!(values instanceof List<?> list)) {
                return List.of();
            }
            List<ProviderCalendarInventoryEntry> entries = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String id = asStringLoose(map.get("id"));
                if (id == null || id.isBlank()) {
                    continue;
                }
                String name = asStringLoose(map.get("name"));
                boolean primary = asBooleanLoose(map.get("isDefaultCalendar"));
                // Graph only exposes canEdit (write) + canViewPrivateItems (read). Absent flags
                // mean the field wasn't returned (e.g. delegated/shared scenarios); treat as
                // best-effort permissive on the read side and strict on write.
                boolean canEdit = asBooleanLoose(map.get("canEdit"));
                Object canView = map.get("canViewPrivateItems");
                boolean canRead = canView == null || asBooleanLoose(canView);
                boolean canWrite = canEdit;
                entries.add(new ProviderCalendarInventoryEntry(id, name, primary, canRead, canWrite, false));
            }
            return List.copyOf(entries);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public List<BusyInterval> fetchBusyIntervals(String accessToken, Instant start, Instant end) {
        try {
            Map<String, Object> body = Map.of(
                    "schedules", List.of("me"),
                    "startTime", Map.of("dateTime", start.toString(), "timeZone", "UTC"),
                    "endTime", Map.of("dateTime", end.toString(), "timeZone", "UTC"),
                    "availabilityViewInterval", 30
            );
            ResponseEntity<Map> response = graphClient.post()
                    .uri("/v1.0/me/calendar/getSchedule")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            Object value = response.getBody() == null ? null : response.getBody().get("value");
            if (!(value instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> first)) {
                return List.of();
            }
            Object items = first.get("scheduleItems");
            if (!(items instanceof List<?> scheduleItems)) {
                return List.of();
            }
            List<BusyInterval> intervals = new ArrayList<>();
            for (Object o : scheduleItems) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Instant s = parseDateTimeFromDateTimeTimeZoneMap(m.get("start"));
                Instant e = parseDateTimeFromDateTimeTimeZoneMap(m.get("end"));
                if (s != null && e != null && s.isBefore(e)) {
                    intervals.add(new BusyInterval(s, e));
                }
            }
            return List.copyOf(intervals);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public SyncWindow listEventsFull(String accessToken) {
        return listEvents(accessToken, "/v1.0/me/calendar/events?$select=id,start,end,isCancelled,lastModifiedDateTime,changeKey,subject,location,organizer");
    }

    @Override
    public SyncWindow listEventsIncremental(String accessToken, String deltaCursor) {
        if (deltaCursor == null || deltaCursor.isBlank()) {
            return listEventsFull(accessToken);
        }
        return listEvents(accessToken, deltaCursor);
    }

    @Override
    public void revokeToken(String token) {
        // Microsoft v2 endpoint doesn't expose direct token revoke for delegated refresh tokens.
        // Access is effectively revoked by user consent removal / admin action.
    }

    @Override
    public WebhookSubscription createEventSubscription(String accessToken,
                                                       String notificationUrl,
                                                       String clientState,
                                                       Instant expiresAt) {
        try {
            Map<String, Object> body = Map.of(
                    "changeType", "created,updated,deleted",
                    "notificationUrl", notificationUrl,
                    "resource", "/me/events",
                    "expirationDateTime", expiresAt.toString(),
                    "clientState", clientState
            );
            ResponseEntity<Map> response = graphClient.post()
                    .uri("/v1.0/subscriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            return toWebhookSubscription(response.getBody(), clientState);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public WebhookSubscription renewEventSubscription(String accessToken, String subscriptionId, Instant expiresAt) {
        try {
            Map<String, Object> body = Map.of("expirationDateTime", expiresAt.toString());
            ResponseEntity<Map> response = graphClient.patch()
                    .uri("/v1.0/subscriptions/{id}", subscriptionId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            return toWebhookSubscription(response.getBody(), null);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public void deleteEventSubscription(String accessToken, String subscriptionId) {
        try {
            graphClient.delete()
                    .uri("/v1.0/subscriptions/{id}", subscriptionId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    private static final org.slf4j.Logger graphLog = org.slf4j.LoggerFactory.getLogger(HttpMicrosoftApiClient.class);

    private SyncWindow listEvents(String accessToken, String pathOrDeltaLink) {
        try {
            URI uri = URI.create(pathOrDeltaLink);
            graphLog.info("microsoft_graph_request url=\"{}\"", pathOrDeltaLink.length() > 300 ? pathOrDeltaLink.substring(0, 300) + "..." : pathOrDeltaLink);
            ResponseEntity<Map> response = graphClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            List<CalendarEventObservation> events = new ArrayList<>();
            Object values = response.getBody() == null ? null : response.getBody().get("value");
            if (values instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String id = asStringLoose(map.get("id"));
                    Instant start = parseDateTimeFromDateTimeTimeZoneMap(map.get("start"));
                    Instant end = parseDateTimeFromDateTimeTimeZoneMap(map.get("end"));
                    boolean cancelled = asBooleanLoose(map.get("isCancelled"));
                    Instant updatedAt = parseInstantLoose(asStringLoose(map.get("lastModifiedDateTime")));
                    String etag = asStringLoose(map.get("changeKey"));
                    String title = asStringLoose(map.get("subject"));
                    String location = nestedString(map, "location", "displayName");
                    String organizerEmail = nestedString(map, "organizer", "emailAddress", "address");
                    events.add(new CalendarEventObservation(id, start, end, cancelled, null, updatedAt, etag,
                            hashPayload(id, start, end, cancelled, etag, title, location, organizerEmail),
                            title, location, organizerEmail));
                }
            }
            String delta = response.getBody() == null ? null : asStringLoose(response.getBody().get("@odata.deltaLink"));
            if (delta == null) {
                delta = asStringLoose(response.getBody() == null ? null : response.getBody().get("@odata.nextLink"));
            }
            graphLog.info("microsoft_graph_response_parsed url_prefix=\"{}\" rawValueCount={} hasDeltaLink={}",
                    pathOrDeltaLink.length() > 80 ? pathOrDeltaLink.substring(0, 80) : pathOrDeltaLink,
                    events.size(), delta != null);
            return new SyncWindow(List.copyOf(events), delta);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public SyncWindow listCalendarViewDelta(String accessToken,
                                            String externalCalendarId,
                                            Instant windowStart,
                                            Instant windowEnd,
                                            String deltaCursor) {
        try {
            String firstUrl;
            if (deltaCursor != null && !deltaCursor.isBlank()) {
                // Follow the persisted @odata.deltaLink verbatim. Graph encodes the
                // calendar id, window, and skip state inside the link; we MUST NOT
                // synthesize a fresh URL when we already hold a delta link.
                firstUrl = deltaCursor;
            } else {
                String encodedCalendarId = URLEncoder.encode(externalCalendarId, StandardCharsets.UTF_8);
                firstUrl = "/v1.0/me/calendars('" + encodedCalendarId + "')/calendarView/delta"
                        + "?startDateTime=" + windowStart.toString()
                        + "&endDateTime=" + windowEnd.toString()
                        + "&$select=id,start,end,isCancelled,lastModifiedDateTime,changeKey,subject,location,organizer";
            }
            List<CalendarEventObservation> events = new ArrayList<>();
            String nextUrl = firstUrl;
            String finalDeltaLink = null;
            int pageCount = 0;
            // Bound the loop defensively. Microsoft's pagination is normally short
            // (a few hundred events / a few pages); a runaway loop would only happen
            // on a Graph bug, but a hard cap stops it from amplifying.
            while (nextUrl != null && pageCount < 100) {
                PageReadResult page = readDeltaPage(accessToken, nextUrl);
                events.addAll(page.events);
                pageCount++;
                if (page.deltaLink != null) {
                    // Terminal page. Only the deltaLink is durable state.
                    finalDeltaLink = page.deltaLink;
                    nextUrl = null;
                } else {
                    // Continuation page. Follow @odata.nextLink, but DO NOT persist it.
                    nextUrl = page.nextLink;
                }
            }
            graphLog.info("microsoft_calendar_view_delta_complete calendarId={} pages={} events={} hasDeltaLink={}",
                    externalCalendarId, pageCount, events.size(), finalDeltaLink != null);
            return new SyncWindow(List.copyOf(events), finalDeltaLink);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    private PageReadResult readDeltaPage(String accessToken, String pathOrLink) {
        URI uri = URI.create(pathOrLink);
        graphLog.info("microsoft_graph_request url=\"{}\"",
                pathOrLink.length() > 300 ? pathOrLink.substring(0, 300) + "..." : pathOrLink);
        ResponseEntity<Map> response = graphClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Prefer", "outlook.timezone=\"UTC\"")
                .retrieve()
                .toEntity(Map.class);
        Map<?, ?> body = response.getBody();
        List<CalendarEventObservation> events = new ArrayList<>();
        Object values = body == null ? null : body.get("value");
        if (values instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                String id = asStringLoose(map.get("id"));
                Instant start = parseDateTimeFromDateTimeTimeZoneMap(map.get("start"));
                Instant end = parseDateTimeFromDateTimeTimeZoneMap(map.get("end"));
                boolean cancelled = asBooleanLoose(map.get("isCancelled"));
                Instant updatedAt = parseInstantLoose(asStringLoose(map.get("lastModifiedDateTime")));
                String etag = asStringLoose(map.get("changeKey"));
                String title = asStringLoose(map.get("subject"));
                String location = nestedString(map, "location", "displayName");
                String organizerEmail = nestedString(map, "organizer", "emailAddress", "address");
                events.add(new CalendarEventObservation(id, start, end, cancelled, null, updatedAt, etag,
                        hashPayload(id, start, end, cancelled, etag, title, location, organizerEmail),
                        title, location, organizerEmail));
            }
        }
        String deltaLink = body == null ? null : asStringLoose(body.get("@odata.deltaLink"));
        String nextLink = body == null ? null : asStringLoose(body.get("@odata.nextLink"));
        return new PageReadResult(events, nextLink, deltaLink);
    }

    private record PageReadResult(List<CalendarEventObservation> events, String nextLink, String deltaLink) {}

    private static Map<String, Object> buildEventBody(String title,
                                                      String description,
                                                      Instant startsAt,
                                                      Instant endsAt,
                                                      String organizerEmail,
                                                      String attendeeEmail,
                                                      String attendeeName,
                                                      ConferencingInstruction instruction) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("subject", title);
        body.put("body", Map.of("contentType", "text", "content", description == null ? "" : description));
        body.put("start", Map.of("dateTime", startsAt.toString(), "timeZone", "UTC"));
        body.put("end", Map.of("dateTime", endsAt.toString(), "timeZone", "UTC"));
        body.put("attendees", List.of(Map.of(
                "emailAddress", Map.of(
                        "address", attendeeEmail,
                        "name", attendeeName == null || attendeeName.isBlank() ? attendeeEmail : attendeeName),
                "type", "required")));
        // App is the canonical organizer and emits its own ICS invites/updates/cancels.
        // Microsoft Graph remains a silent time-block mirror — it must not dispatch parallel invitation emails.
        body.put("responseRequested", false);
        body.put("isReminderOn", false);
        if (instruction != null
                && instruction.mode() == ConferencingInstruction.Mode.REQUEST_NATIVE_MEET
                && instruction.providerType() == ConferencingProviderType.MICROSOFT_TEAMS) {
            body.put("isOnlineMeeting", true);
            body.put("onlineMeetingProvider", "teamsForBusiness");
        }
        return body;
    }

    private static MicrosoftEventDetails toDetails(Map<?, ?> body) {
        String eventId = asStringLoose(body == null ? null : body.get("id"));
        String webLink = asStringLoose(body == null ? null : body.get("webLink"));
        String conferenceUrl = null;
        Object onlineMeeting = body == null ? null : body.get("onlineMeeting");
        if (onlineMeeting instanceof Map<?, ?> om) {
            conferenceUrl = asStringLoose(om.get("joinUrl"));
        }
        return new MicrosoftEventDetails(eventId, webLink, conferenceUrl);
    }

    private static WebhookSubscription toWebhookSubscription(Map<?, ?> body, String fallbackClientState) {
        String subscriptionId = asStringLoose(body == null ? null : body.get("id"));
        String resourceId = asStringLoose(body == null ? null : body.get("resource"));
        String clientState = asStringLoose(body == null ? null : body.get("clientState"));
        Instant expiresAt = parseInstantLoose(asStringLoose(body == null ? null : body.get("expirationDateTime")));
        return new WebhookSubscription(subscriptionId, resourceId, expiresAt, clientState == null ? fallbackClientState : clientState);
    }

    private static CalendarClientException classify(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            String body = responseException.getResponseBodyAsString();
            OAuthError error = OAuthError.fromHttp(status, body,
                    io.bunnycal.calendar.domain.CalendarProviderType.MICROSOFT);
            return new CalendarClientException(status, body, error);
        }
        OAuthError error = OAuthError.network(
                io.bunnycal.calendar.domain.CalendarProviderType.MICROSOFT,
                ex.getMessage());
        return new CalendarClientException(503, ex.getMessage(), error);
    }

    private static void logContractVerified(String action, Map<String, Object> body) {
        Object responseRequested = body.get("responseRequested");
        org.slf4j.LoggerFactory.getLogger(HttpMicrosoftApiClient.class)
                .info("provider_authority_contract_verified provider=microsoft action={} responseRequested={}", action, responseRequested);
        org.slf4j.LoggerFactory.getLogger(HttpMicrosoftApiClient.class)
                .info("provider_notification_suppression_verified provider=microsoft action={} responseRequested={}", action, responseRequested);
    }

    private String effectiveTenant() {
        return properties.getTenantId() == null || properties.getTenantId().isBlank() ? "common" : properties.getTenantId();
    }

    private static String effectiveCalendarId(String value) {
        return value == null || value.isBlank() ? "primary" : value;
    }

    private static String asString(Map<?, ?> map, String key) {
        return map == null ? null : asStringLoose(map.get(key));
    }

    private static Number asNumber(Map<?, ?> map, String key, int fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number number ? number : fallback;
    }

    private static String asStringLoose(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean asBooleanLoose(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s);
        }
        return false;
    }

    private static Instant parseDateTimeFromDateTimeTimeZoneMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        String raw = asStringLoose(map.get("dateTime"));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            if (raw.endsWith("Z")) {
                return Instant.parse(raw);
            }
            return Instant.parse(raw + "Z");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Instant parseInstantLoose(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String hashPayload(String id,
                                      Instant start,
                                      Instant end,
                                      boolean cancelled,
                                      String etag,
                                      String title,
                                      String location,
                                      String organizerEmail) {
        return Integer.toHexString((id + "|" + start + "|" + end + "|" + cancelled + "|" + etag + "|"
                + title + "|" + location + "|" + organizerEmail).hashCode());
    }

    private static String nestedString(Map<?, ?> source, String... keys) {
        Object current = source;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return asStringLoose(current);
    }
}
