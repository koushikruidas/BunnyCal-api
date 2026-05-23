package com.daedalussystems.easySchedule.booking.controller;

import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.dto.BookingResponse;
import com.daedalussystems.easySchedule.booking.dto.CreateBookingRequest;
import com.daedalussystems.easySchedule.booking.dto.MeetingSummaryResponse;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyOutcome;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyRoutes;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyService;
import com.daedalussystems.easySchedule.booking.idempotency.ResponseEnvelope;
import com.daedalussystems.easySchedule.booking.service.BookingService;
import com.daedalussystems.easySchedule.booking.service.MeetingQueryService;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.time.TimeConversionService;
import com.daedalussystems.easySchedule.common.util.RequestHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Slf4j
public class BookingController {

    private static final String ROUTE = IdempotencyRoutes.API_BOOKINGS_CREATE;
    private static final String CANCEL_ROUTE = IdempotencyRoutes.API_BOOKINGS_CANCEL;

    private final BookingService bookingService;
    private final MeetingQueryService meetingQueryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TimeConversionService timeConversionService;

    @GetMapping("/hosts/{hostId}/meetings")
    public ResponseEntity<ApiResponse<List<MeetingSummaryResponse>>> listHostMeetings(
            Authentication authentication,
            @PathVariable UUID hostId,
            @RequestParam(name = "upcomingOnly", defaultValue = "true") boolean upcomingOnly,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UUID authenticatedUserId = extractUserId(authentication);
        if (!authenticatedUserId.equals(hostId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        List<MeetingSummaryResponse> meetings = meetingQueryService.listHostMeetings(hostId, upcomingOnly, limit);
        return ResponseEntity.ok(ApiResponse.success(meetings));
    }

    @GetMapping("/me/meetings")
    public ResponseEntity<ApiResponse<List<MeetingSummaryResponse>>> listMyMeetings(
            Authentication authentication,
            @RequestParam(name = "upcomingOnly", defaultValue = "true") boolean upcomingOnly,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UUID authenticatedUserId = extractUserId(authentication);
        List<MeetingSummaryResponse> meetings = meetingQueryService.listHostMeetings(authenticatedUserId, upcomingOnly, limit);
        return ResponseEntity.ok(ApiResponse.success(meetings));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Timezone", required = false) String timezoneHeader,
            @RequestBody CreateBookingRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        Instant normalizedStart = timeConversionService.normalizeClientInstant(request.startTime(), timezoneHeader);
        Instant normalizedEnd = timeConversionService.normalizeClientInstant(request.endTime(), timezoneHeader);
        log.info("api_booking_create_time_normalization hostId={} eventTypeId={} timezoneHeader={} rawStartTime={} normalizedStartTime={} rawEndTime={} normalizedEndTime={} temporalType={}",
                request.hostId(),
                request.eventTypeId(),
                timezoneHeader,
                request.startTime(),
                normalizedStart,
                request.endTime(),
                normalizedEnd,
                normalizedStart == null ? "null" : normalizedStart.getClass().getSimpleName());
        CreateBookingRequest normalizedRequest =
                new CreateBookingRequest(request.hostId(), request.eventTypeId(), normalizedStart, normalizedEnd);

        String requestHash = RequestHasher.hash(normalizedRequest, objectMapper);
        // Known debt: idempotency scope is the auth principal, not the
        // host. The current API uses request.hostId() for both — treat
        // as a separate refactor (auth subject vs. booking target).
        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                request.hostId(),
                ROUTE,
                requestHash,
                () -> {
                    Booking saved = bookingService.createBooking(
                            normalizedRequest.hostId(),
                            normalizedRequest.eventTypeId(),
                            normalizedRequest.startTime(),
                            normalizedRequest.endTime(),
                            null,
                            null);
                    return new ResponseEnvelope<>(201, BookingResponse.from(saved));
                });

        return outcome.toResponseEntity(objectMapper);
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<?> cancel(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        UUID authenticatedUserId = extractUserId(authentication);
        String requestHash = RequestHasher.hash(Map.of(
                "bookingId", bookingId,
                "actorUserId", authenticatedUserId
        ), objectMapper);

        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                authenticatedUserId,
                CANCEL_ROUTE,
                requestHash,
                () -> {
                    Booking booking = bookingService.cancelBookingAsHost(bookingId, authenticatedUserId, null);
                    return new ResponseEnvelope<>(200, BookingResponse.from(booking));
                });
        return outcome.toResponseEntity(objectMapper);
    }

    private static UUID extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID uuid) {
            return uuid;
        }
        if (principal instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                throw new CustomException(ErrorCode.UNAUTHORIZED);
            }
        }
        throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
