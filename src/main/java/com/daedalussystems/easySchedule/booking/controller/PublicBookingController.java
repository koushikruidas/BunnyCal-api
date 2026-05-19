package com.daedalussystems.easySchedule.booking.controller;

import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicConfirmResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicBookRequest;
import com.daedalussystems.easySchedule.booking.dto.PublicEventInfoResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicManageBookingResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicRescheduleRequest;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyOutcome;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyRoutes;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyService;
import com.daedalussystems.easySchedule.booking.idempotency.ResponseEnvelope;
import com.daedalussystems.easySchedule.booking.service.PublicBookingService;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.time.TimeConversionService;
import com.daedalussystems.easySchedule.common.util.RequestHasher;
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

@RestController
@RequestMapping("/public")
public class PublicBookingController {
    private static final Logger log = LoggerFactory.getLogger(PublicBookingController.class);
    private final PublicBookingService publicBookingService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TimeConversionService timeConversionService;

    public PublicBookingController(PublicBookingService publicBookingService,
                                   IdempotencyService idempotencyService,
                                   ObjectMapper objectMapper,
                                   TimeConversionService timeConversionService) {
        this.publicBookingService = publicBookingService;
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

    @PostMapping("/{username}/{eventTypeSlug}/book")
    public ResponseEntity<?> hold(@PathVariable String username,
                                  @PathVariable String eventTypeSlug,
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
                request.guestName());

        String route = IdempotencyRoutes.PUBLIC_BOOK_HOLD;
        Map<String, Object> hashPayload = new java.util.LinkedHashMap<>();
        hashPayload.put("username", username);
        hashPayload.put("eventTypeSlug", eventTypeSlug);
        hashPayload.put("startTime", normalizedRequest.startTime());
        hashPayload.put("guestEmail", normalizeGuestEmail(request.guestEmail()));
        hashPayload.put("guestName", normalizeGuestName(request.guestName()));
        String requestHash = RequestHasher.hash(hashPayload, objectMapper);

        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                routeScopeUser(username, eventTypeSlug),
                route,
                requestHash,
                () -> new ResponseEnvelope<>(201, publicBookingService.hold(username, eventTypeSlug, normalizedRequest))
        );

        return outcome.toResponseEntity(objectMapper);
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
}
