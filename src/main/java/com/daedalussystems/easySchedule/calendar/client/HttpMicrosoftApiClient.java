package com.daedalussystems.easySchedule.calendar.client;

import com.daedalussystems.easySchedule.calendar.config.MicrosoftOAuthProperties;
import com.daedalussystems.easySchedule.calendar.provider.CreateEventRequest;
import com.daedalussystems.easySchedule.calendar.provider.UpdateEventRequest;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpMicrosoftApiClient implements MicrosoftApiClient {
    private static final Logger log = LoggerFactory.getLogger(HttpMicrosoftApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        Map<String, Object> body = buildEventBody(request.title(), request.description(), request.startsAt(), request.endsAt(),
                request.organizerEmail(), request.attendeeEmail(), request.attendeeName(), request.conferencingInstruction());
        String calendarId = graphCalendarId(request.targetCalendarId());
        String path = eventsPath(calendarId);
        TokenClaims claims = parseTokenClaims(accessToken);
        log.info("microsoft_graph_path_resolved operation=createEvent incomingCalendarId={} normalizedCalendarId={} resolvedPath={}",
                request.targetCalendarId(), calendarId, path);
        log.info("microsoft_graph_account_classification operation=createEvent tid={} iss={} upn={} accountClassification={}",
                claims.tid(), claims.iss(), claims.upn() == null ? null : maskEmail(claims.upn()), claims.classification());
        log.info("microsoft_graph_request_payload operation=createEvent path={} payload={}",
                path, sanitizePayload(body));
        try {
            ResponseEntity<Map> response = graphClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("Prefer", "outlook.timezone=\"UTC\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            logGraphResponse("createEvent", path, response.getBody());
            return toDetails(response.getBody());
        } catch (RestClientResponseException ex) {
            throw logAndClassify("createEvent", path, body, ex);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public MicrosoftEventDetails updateEvent(String accessToken, UpdateEventRequest request) {
        Map<String, Object> body = buildEventBody(request.title(), request.description(), request.startsAt(), request.endsAt(),
                request.organizerEmail(), request.attendeeEmail(), request.attendeeName(), request.conferencingInstruction());
        String calendarId = graphCalendarId(request.targetCalendarId());
        String path = eventPath(calendarId, request.externalEventId());
        TokenClaims claims = parseTokenClaims(accessToken);
        log.info("microsoft_graph_path_resolved operation=updateEvent incomingCalendarId={} normalizedCalendarId={} resolvedPath={}",
                request.targetCalendarId(), calendarId, path);
        log.info("microsoft_graph_account_classification operation=updateEvent tid={} iss={} upn={} accountClassification={}",
                claims.tid(), claims.iss(), claims.upn() == null ? null : maskEmail(claims.upn()), claims.classification());
        log.info("microsoft_graph_request_payload operation=updateEvent path={} payload={}",
                path, sanitizePayload(body));
        try {
            ResponseEntity<Map> response = graphClient.patch()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("Prefer", "outlook.timezone=\"UTC\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            logGraphResponse("updateEvent", path, response.getBody());
            return toDetails(response.getBody());
        } catch (RestClientResponseException ex) {
            throw logAndClassify("updateEvent", path, body, ex);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public void deleteEvent(String accessToken, String targetCalendarId, String externalEventId) {
        String calendarId = graphCalendarId(targetCalendarId);
        String path = eventPath(calendarId, externalEventId);
        log.info("microsoft_graph_path_resolved operation=deleteEvent incomingCalendarId={} normalizedCalendarId={} resolvedPath={}",
                targetCalendarId, calendarId, path);
        try {
            graphClient.delete()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw logAndClassify("deleteEvent", path, null, ex);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    @Override
    public boolean eventExists(String accessToken, String targetCalendarId, String externalEventId) {
        String calendarId = graphCalendarId(targetCalendarId);
        String path = eventPath(calendarId, externalEventId);
        log.info("microsoft_graph_path_resolved operation=eventExists incomingCalendarId={} normalizedCalendarId={} resolvedPath={}",
                targetCalendarId, calendarId, path);
        try {
            graphClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            return true;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 404 || status == 410) {
                return false;
            }
            throw logAndClassify("eventExists", path, null, ex);
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
            return new TokenRefreshResult(accessToken, Instant.now().plusSeconds(expiresIn.longValue()));
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
    public ProviderUserProfile fetchProviderUserProfile(String accessToken) {
        try {
            ResponseEntity<Map> response = graphClient.get()
                    .uri("/v1.0/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(Map.class);
            Map<?, ?> body = response.getBody();
            return new ProviderUserProfile(
                    asString(body, "id"),
                    asString(body, "userPrincipalName"),
                    asString(body, "mail"));
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
        return listEvents(accessToken, "/v1.0/me/calendar/events?$select=id,start,end,isCancelled,lastModifiedDateTime,changeKey");
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

    // Graph deltaLink / nextLink values are opaque, already-encoded URIs. They MUST be
    // sent verbatim — RestClient's String-uri overload runs URI-template expansion and
    // re-encodes literal '%' characters, causing the cursor to grow as %25 → %2525 → …
    // each poll until it exceeds the IIS request-header limit (~16KB) and Graph returns
    // HTTP 400 "Request Too Long". The java.net.URI overload bypasses templating.
    private static final int CURSOR_MAX_LEN = 4096;

    private SyncWindow listEvents(String accessToken, String pathOrDeltaLink) {
        if (pathOrDeltaLink != null && pathOrDeltaLink.length() > CURSOR_MAX_LEN) {
            log.warn("microsoft_graph_cursor_oversized length={} head={} reason=likely_double_encoded",
                    pathOrDeltaLink.length(), pathOrDeltaLink.substring(0, 200));
            // Treat as invalid token; surface as 410 so upstream forces a full resync and clears the cursor.
            throw new CalendarClientException(410, "microsoft sync cursor exceeds " + CURSOR_MAX_LEN + " chars; treating as invalid");
        }
        URI uri = resolveListUri(pathOrDeltaLink);
        try {
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
                    events.add(new CalendarEventObservation(id, start, end, cancelled, null, updatedAt, etag, hashPayload(id, start, end, cancelled, etag)));
                }
            }
            String delta = response.getBody() == null ? null : asStringLoose(response.getBody().get("@odata.deltaLink"));
            if (delta == null) {
                delta = asStringLoose(response.getBody() == null ? null : response.getBody().get("@odata.nextLink"));
            }
            return new SyncWindow(List.copyOf(events), delta);
        } catch (RestClientResponseException ex) {
            log.warn("microsoft_graph_list_failed url={} urlLen={} status={} body={}",
                    uri, uri.toString().length(), ex.getStatusCode().value(),
                    truncate(ex.getResponseBodyAsString(), 500));
            throw classify(ex);
        } catch (RestClientException ex) {
            throw classify(ex);
        }
    }

    private URI resolveListUri(String pathOrDeltaLink) {
        if (pathOrDeltaLink == null || pathOrDeltaLink.isBlank()) {
            return URI.create("https://graph.microsoft.com/v1.0/me/calendar/events");
        }
        if (pathOrDeltaLink.startsWith("http://") || pathOrDeltaLink.startsWith("https://")) {
            // Graph @odata.{next,delta}Link — opaque, already encoded. Send verbatim.
            return URI.create(pathOrDeltaLink);
        }
        // Relative path — prefix the Graph base. Still bypasses template expansion.
        return URI.create("https://graph.microsoft.com" + pathOrDeltaLink);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max) + "...[truncated]";
    }

    private static Map<String, Object> buildEventBody(String title,
                                                      String description,
                                                      Instant startsAt,
                                                      Instant endsAt,
                                                      String organizerEmail,
                                                      String attendeeEmail,
                                                      String attendeeName,
                                                      ConferencingInstruction instruction) {
        ConferencingInstruction effective = instruction == null ? ConferencingInstruction.none() : instruction;
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("subject", title);
        body.put("body", Map.of("contentType", "text", "content", appendConferenceUrl(description, effective)));
        body.put("start", Map.of("dateTime", startsAt.toString(), "timeZone", "UTC"));
        body.put("end", Map.of("dateTime", endsAt.toString(), "timeZone", "UTC"));
        body.put("attendees", List.of(Map.of(
                "emailAddress", Map.of(
                        "address", attendeeEmail,
                        "name", attendeeName == null || attendeeName.isBlank() ? attendeeEmail : attendeeName),
                "type", "required")));
        if (effective.embedsExternalUrl()) {
            body.put("location", Map.of("displayName", effective.joinUrl()));
        }
        if (effective.mode() == ConferencingInstruction.Mode.REQUEST_NATIVE_MEET
                && effective.providerType() == com.daedalussystems.easySchedule.common.enums.ConferencingProviderType.MICROSOFT_TEAMS) {
            body.put("isOnlineMeeting", true);
            body.put("onlineMeetingProvider", "teamsForBusiness");
        }
        return body;
    }

    private static String appendConferenceUrl(String description, ConferencingInstruction instruction) {
        String base = description == null ? "" : description.trim();
        if (!instruction.embedsExternalUrl()) {
            return base;
        }
        String joinLine = "Join URL: " + instruction.joinUrl();
        if (base.isBlank()) {
            return joinLine;
        }
        if (base.contains(instruction.joinUrl())) {
            return base;
        }
        return base + "\n" + joinLine;
    }

    private static MicrosoftEventDetails toDetails(Map<?, ?> body) {
        String eventId = asStringLoose(body == null ? null : body.get("id"));
        String webLink = asStringLoose(body == null ? null : body.get("webLink"));
        String conferenceUrl = null;
        Object onlineMeeting = body == null ? null : body.get("onlineMeeting");
        if (onlineMeeting instanceof Map<?, ?> om) {
            conferenceUrl = asStringLoose(om.get("joinUrl"));
        }
        String organizerEmail = null;
        Object organizer = body == null ? null : body.get("organizer");
        if (organizer instanceof Map<?, ?> om) {
            Object ea = om.get("emailAddress");
            if (ea instanceof Map<?, ?> eam) {
                organizerEmail = asStringLoose(eam.get("address"));
            }
        }
        return new MicrosoftEventDetails(eventId, webLink, conferenceUrl, organizerEmail);
    }

    private static WebhookSubscription toWebhookSubscription(Map<?, ?> body, String fallbackClientState) {
        String subscriptionId = asStringLoose(body == null ? null : body.get("id"));
        String resourceId = asStringLoose(body == null ? null : body.get("resource"));
        String clientState = asStringLoose(body == null ? null : body.get("clientState"));
        Instant expiresAt = parseInstantLoose(asStringLoose(body == null ? null : body.get("expirationDateTime")));
        return new WebhookSubscription(subscriptionId, resourceId, expiresAt, clientState == null ? fallbackClientState : clientState);
    }

    private CalendarClientException logAndClassify(String operation, String path,
                                                    Map<String, Object> payload,
                                                    RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String rawBody = ex.getResponseBodyAsString();
        String graphCode = null;
        String graphMessage = null;
        String graphInnerError = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(rawBody, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) parsed.get("error");
            if (error != null) {
                graphCode = asStringLoose(error.get("code"));
                graphMessage = asStringLoose(error.get("message"));
                Object inner = error.get("innerError");
                if (inner != null) {
                    graphInnerError = inner instanceof Map<?, ?> m ? toJson(m) : String.valueOf(inner);
                }
            }
        } catch (Exception ignored) {
            // rawBody is not JSON — log as-is below
        }
        log.error("microsoft_graph_request_failed operation={} url={} status={} graphCode={} graphMessage={} graphInnerError={} payload={}",
                operation, path, status, graphCode, graphMessage, graphInnerError, sanitizePayload(payload));
        return new CalendarClientException(status, rawBody);
    }

    private static CalendarClientException classify(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return new CalendarClientException(responseException.getStatusCode().value(),
                    responseException.getResponseBodyAsString());
        }
        return new CalendarClientException(503, ex.getMessage());
    }

    private static void logGraphResponse(String operation, String path, Map<?, ?> body) {
        if (body == null) {
            log.info("microsoft_graph_response operation={} path={} body=null", operation, path);
            return;
        }
        String id = asStringLoose(body.get("id"));
        String webLink = asStringLoose(body.get("webLink"));
        String iCalUId = asStringLoose(body.get("iCalUId"));
        String type = asStringLoose(body.get("type"));
        String sensitivity = asStringLoose(body.get("sensitivity"));
        Object isCancelled = body.get("isCancelled");
        Object isOrganizer = body.get("isOrganizer");
        Object responseRequested = body.get("responseRequested");
        Object isOnlineMeeting = body.get("isOnlineMeeting");
        String onlineMeetingProvider = asStringLoose(body.get("onlineMeetingProvider"));

        String organizerAddress = null;
        Object organizer = body.get("organizer");
        if (organizer instanceof Map<?, ?> om) {
            Object ea = om.get("emailAddress");
            if (ea instanceof Map<?, ?> eam) {
                organizerAddress = maskEmail(asStringLoose(eam.get("address")));
            }
        }

        String onlineMeetingJoinUrl = null;
        Object onlineMeeting = body.get("onlineMeeting");
        if (onlineMeeting instanceof Map<?, ?> omm) {
            onlineMeetingJoinUrl = asStringLoose(omm.get("joinUrl"));
        }

        int attendeeCount = 0;
        StringBuilder attendeeSummary = new StringBuilder();
        Object attendees = body.get("attendees");
        if (attendees instanceof List<?> list) {
            attendeeCount = list.size();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String aType = asStringLoose(m.get("type"));
                String addr = null;
                Object ea = m.get("emailAddress");
                if (ea instanceof Map<?, ?> eam) {
                    addr = maskEmail(asStringLoose(eam.get("address")));
                }
                String response = null;
                Object status = m.get("status");
                if (status instanceof Map<?, ?> sm) {
                    response = asStringLoose(sm.get("response"));
                }
                if (attendeeSummary.length() > 0) attendeeSummary.append(",");
                attendeeSummary.append(addr).append("(").append(aType).append("/").append(response).append(")");
            }
        }

        log.info("microsoft_graph_response operation={} path={} id={} iCalUId={} webLink={} type={} sensitivity={} isCancelled={} isOrganizer={} responseRequested={} organizerEmail={} isOnlineMeeting={} onlineMeetingProvider={} onlineMeetingJoinUrl={} attendeeCount={} attendees=[{}]",
                operation, path, id, iCalUId, webLink, type, sensitivity,
                isCancelled, isOrganizer, responseRequested, organizerAddress,
                isOnlineMeeting, onlineMeetingProvider,
                onlineMeetingJoinUrl == null ? "[absent]" : "[present]",
                attendeeCount, attendeeSummary);
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    // Microsoft consumer (MSA) tenant id — every MSA token carries this tid.
    private static final String MSA_TENANT_ID = "9188040d-6c67-4c5b-b112-36a304b66dad";

    private record TokenClaims(String tid, String iss, String upn, String classification) {}

    private static TokenClaims parseTokenClaims(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return new TokenClaims(null, null, null, "UNKNOWN");
        }
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return new TokenClaims(null, null, null, "OPAQUE_TOKEN");
            }
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = MAPPER.readValue(decoded, Map.class);
            String tid = asStringLoose(payload.get("tid"));
            String iss = asStringLoose(payload.get("iss"));
            String upn = asStringLoose(payload.get("upn"));
            if (upn == null) upn = asStringLoose(payload.get("preferred_username"));
            if (upn == null) upn = asStringLoose(payload.get("email"));
            String classification = classifyAccount(tid, iss);
            return new TokenClaims(tid, iss, upn, classification);
        } catch (RuntimeException | java.io.IOException ex) {
            return new TokenClaims(null, null, null, "PARSE_FAILED");
        }
    }

    private static String classifyAccount(String tid, String iss) {
        if (tid == null) return "UNKNOWN";
        if (MSA_TENANT_ID.equalsIgnoreCase(tid)) return "PERSONAL_MSA";
        // anything that isn't the consumer tenant id is an AAD/Entra tenant
        return "AAD_WORK_SCHOOL";
    }

    private static String sanitizePayload(Map<String, Object> payload) {
        if (payload == null) {
            return "null";
        }
        // Deep-copy and mask attendee email addresses before logging
        try {
            String raw = MAPPER.writeValueAsString(payload);
            // Replace quoted email-like values with masked form
            return raw.replaceAll("\"([^\"@]{1,2})[^\"@]*@([^\"]+)\"", "\"$1***@$2\"");
        } catch (JsonProcessingException e) {
            return "[unserializable]";
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String effectiveTenant() {
        return properties.getTenantId() == null || properties.getTenantId().isBlank() ? "common" : properties.getTenantId();
    }

    // Returns null when the caller should use the /me/events default-calendar path.
    // Graph has no "primary" alias — passing "primary" as a calendar ID returns ErrorInvalidIdMalformed.
    private static String graphCalendarId(String value) {
        return value == null || value.isBlank() || "primary".equalsIgnoreCase(value) ? null : value;
    }

    // Produces a pre-encoded literal path — no UriTemplate expansion needed.
    private static String eventsPath(String calendarId) {
        return calendarId == null
                ? "/v1.0/me/events"
                : "/v1.0/me/calendars/" + java.net.URLEncoder.encode(calendarId, java.nio.charset.StandardCharsets.UTF_8) + "/events";
    }

    private static String eventPath(String calendarId, String eventId) {
        String encodedEvent = java.net.URLEncoder.encode(eventId, java.nio.charset.StandardCharsets.UTF_8);
        return calendarId == null
                ? "/v1.0/me/events/" + encodedEvent
                : "/v1.0/me/calendars/" + java.net.URLEncoder.encode(calendarId, java.nio.charset.StandardCharsets.UTF_8) + "/events/" + encodedEvent;
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

    private static String hashPayload(String id, Instant start, Instant end, boolean cancelled, String etag) {
        return Integer.toHexString((id + "|" + start + "|" + end + "|" + cancelled + "|" + etag).hashCode());
    }
}
