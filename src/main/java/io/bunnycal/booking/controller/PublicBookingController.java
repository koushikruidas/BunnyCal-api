package io.bunnycal.booking.controller;

import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.booking.dto.PublicConfirmResponse;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.dto.PublicEventInfoResponse;
import io.bunnycal.booking.dto.PublicGroupSessionsResponse;
import io.bunnycal.booking.dto.PublicManageBookingResponse;
import io.bunnycal.booking.dto.PublicRescheduleRequest;
import io.bunnycal.booking.idempotency.IdempotencyOutcome;
import io.bunnycal.booking.idempotency.IdempotencyRoutes;
import io.bunnycal.booking.idempotency.IdempotencyService;
import io.bunnycal.booking.idempotency.ResponseEnvelope;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.booking.service.PublicGroupSessionQueryService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeConversionService;
import io.bunnycal.common.util.RequestHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/public")
public class PublicBookingController {
    private static final Logger log = LoggerFactory.getLogger(PublicBookingController.class);
    private final PublicBookingService publicBookingService;
    private final PublicGroupSessionQueryService publicGroupSessionQueryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TimeConversionService timeConversionService;

    public PublicBookingController(PublicBookingService publicBookingService,
                                   PublicGroupSessionQueryService publicGroupSessionQueryService,
                                   IdempotencyService idempotencyService,
                                   ObjectMapper objectMapper,
                                   TimeConversionService timeConversionService) {
        this.publicBookingService = publicBookingService;
        this.publicGroupSessionQueryService = publicGroupSessionQueryService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.timeConversionService = timeConversionService;
    }

    @GetMapping("/{username}/{eventTypeSlug}/availability")
    public ResponseEntity<ApiResponse<SlotResponse>> availability(@PathVariable String username,
                                                                  @PathVariable String eventTypeSlug,
                                                                  @RequestParam("date")
                                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                                  LocalDate date) {
        if (date == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "date is required.");
        }
        return ResponseEntity.ok(ApiResponse.success(publicBookingService.availability(username, eventTypeSlug, date)));
    }

    @GetMapping("/{username}/{eventTypeSlug}")
    public ResponseEntity<ApiResponse<PublicEventInfoResponse>> eventInfo(@PathVariable String username,
                                                                          @PathVariable String eventTypeSlug) {
        return ResponseEntity.ok(ApiResponse.success(publicBookingService.eventInfo(username, eventTypeSlug)));
    }

    @GetMapping("/{username}/{eventTypeSlug}/group-sessions")
    public ResponseEntity<ApiResponse<PublicGroupSessionsResponse>> groupSessions(
            @PathVariable String username,
            @PathVariable String eventTypeSlug,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(value = "days", required = false) Integer days) {
        return ResponseEntity.ok(ApiResponse.success(
                publicGroupSessionQueryService.getGroupSessions(username, eventTypeSlug, startDate, days)));
    }

    @PostMapping("/{username}/{eventTypeSlug}/book")
    public ResponseEntity<?> hold(@PathVariable String username,
                                  @PathVariable String eventTypeSlug,
                                  Authentication authentication,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @RequestHeader(value = "X-Timezone", required = false) String timezoneHeader,
                                  @RequestBody PublicBookRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        Instant normalizedStart = timeConversionService.normalizeClientInstant(request.startTime(), timezoneHeader);
        log.info("public_booking_hold_time_normalization username={} eventTypeSlug={} timezoneHeader={} rawStartTime={} normalizedStartTime={} temporalType={}",
                username,
                eventTypeSlug,
                timezoneHeader,
                request.startTime(),
                normalizedStart,
                normalizedStart == null ? "null" : normalizedStart.getClass().getSimpleName());
        PublicBookRequest normalizedRequest = new PublicBookRequest(
                normalizedStart,
                request.guestEmail(),
                request.guestName(),
                request.notes(),
                request.slotToken(),
                request.answers(),
                request.embedToken());

        String route = IdempotencyRoutes.PUBLIC_BOOK_HOLD;
        Map<String, Object> hashPayload = new java.util.LinkedHashMap<>();
        hashPayload.put("username", username);
        hashPayload.put("eventTypeSlug", eventTypeSlug);
        hashPayload.put("startTime", normalizedRequest.startTime());
        hashPayload.put("guestEmail", normalizeGuestEmail(request.guestEmail()));
        hashPayload.put("guestName", normalizeGuestName(request.guestName()));
        hashPayload.put("notes", normalizeNotes(request.notes()));
        hashPayload.put("answers", request.answers());
        hashPayload.put("embedToken", request.embedToken());
        hashPayload.put("slotToken", request.slotToken());
        String requestHash = RequestHasher.hash(hashPayload, objectMapper);

        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                routeScopeUser(username, eventTypeSlug),
                route,
                requestHash,
                () -> new ResponseEnvelope<>(201, publicBookingService.hold(
                        username,
                        eventTypeSlug,
                        normalizedRequest,
                        extractOptionalUserId(authentication)))
        );

        return outcome.toResponseEntity(objectMapper);
    }

    public ResponseEntity<?> hold(String username,
                                  String eventTypeSlug,
                                  String idempotencyKey,
                                  String timezoneHeader,
                                  PublicBookRequest request) {
        return hold(username, eventTypeSlug, null, idempotencyKey, timezoneHeader, request);
    }

    @GetMapping("/{username}/{eventTypeSlug}/book/{bookingId}")
    public ResponseEntity<ApiResponse<PublicManageBookingResponse>> manageView(
            @PathVariable String username,
            @PathVariable String eventTypeSlug,
            @PathVariable String bookingId,
            @RequestParam(value = "token", required = false) String guestCapabilityToken) {
        PublicManageBookingResponse response = publicBookingService.manageView(
                username, eventTypeSlug, java.util.UUID.fromString(bookingId), guestCapabilityToken);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{username}/{eventTypeSlug}/book/{bookingId}/confirm")
    public ResponseEntity<ApiResponse<PublicConfirmResponse>> confirm(@PathVariable String username,
                                                                      @PathVariable String eventTypeSlug,
                                                                      @PathVariable String bookingId) {
        PublicConfirmResponse response = publicBookingService.confirm(username, eventTypeSlug, java.util.UUID.fromString(bookingId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{username}/{eventTypeSlug}/book/{bookingId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String username,
                                    @PathVariable String eventTypeSlug,
                                    @PathVariable String bookingId,
                                    @RequestParam(value = "token", required = false) String guestCapabilityToken,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String route = IdempotencyRoutes.PUBLIC_BOOK_CANCEL;
        String requestHash = RequestHasher.hash(Map.of(
                "username", username,
                "eventTypeSlug", eventTypeSlug,
                "bookingId", bookingId,
                "token", guestCapabilityToken == null ? "" : guestCapabilityToken
        ), objectMapper);
        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                routeScopeUser(username, eventTypeSlug),
                route,
                requestHash,
                () -> new ResponseEnvelope<>(200, publicBookingService.cancel(
                        username, eventTypeSlug, java.util.UUID.fromString(bookingId), guestCapabilityToken))
        );
        return outcome.toResponseEntity(objectMapper);
    }

    @PostMapping("/{username}/{eventTypeSlug}/book/{bookingId}/reschedule")
    public ResponseEntity<?> reschedule(@PathVariable String username,
                                        @PathVariable String eventTypeSlug,
                                        @PathVariable String bookingId,
                                        @RequestParam(value = "token", required = false) String guestCapabilityToken,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @RequestHeader(value = "X-Timezone", required = false) String timezoneHeader,
                                        @RequestBody PublicRescheduleRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (request == null || request.startTime() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "startTime is required.");
        }
        Instant normalizedStart = timeConversionService.normalizeClientInstant(request.startTime(), timezoneHeader);
        log.info("public_booking_reschedule_time_normalization username={} eventTypeSlug={} bookingId={} timezoneHeader={} rawStartTime={} normalizedStartTime={} temporalType={}",
                username,
                eventTypeSlug,
                bookingId,
                timezoneHeader,
                request.startTime(),
                normalizedStart,
                normalizedStart == null ? "null" : normalizedStart.getClass().getSimpleName());
        PublicRescheduleRequest normalizedRequest = new PublicRescheduleRequest(normalizedStart);

        String route = IdempotencyRoutes.PUBLIC_BOOK_RESCHEDULE;
        String requestHash = RequestHasher.hash(Map.of(
                "username", username,
                "eventTypeSlug", eventTypeSlug,
                "bookingId", bookingId,
                "startTime", normalizedRequest.startTime(),
                "token", guestCapabilityToken == null ? "" : guestCapabilityToken
        ), objectMapper);

        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                routeScopeUser(username, eventTypeSlug),
                route,
                requestHash,
                () -> new ResponseEnvelope<>(200, publicBookingService.reschedule(
                        username, eventTypeSlug, java.util.UUID.fromString(bookingId), normalizedRequest, guestCapabilityToken))
        );
        return outcome.toResponseEntity(objectMapper);
    }

    private static java.util.UUID routeScopeUser(String username, String eventTypeSlug) {
        return java.util.UUID.nameUUIDFromBytes((username + ":" + eventTypeSlug).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String normalizeGuestEmail(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase();
        return v.isBlank() ? null : v;
    }

    private static String normalizeGuestName(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isBlank() ? null : v;
    }

    private static String normalizeNotes(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isBlank() ? null : v;
    }

    private static java.util.UUID extractOptionalUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof java.util.UUID uuid) {
            return uuid;
        }
        if (principal instanceof String text) {
            try {
                return java.util.UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
