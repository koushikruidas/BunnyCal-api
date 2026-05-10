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
import com.daedalussystems.easySchedule.common.util.RequestHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private static final String ROUTE = IdempotencyRoutes.API_BOOKINGS_CREATE;

    private final BookingService bookingService;
    private final MeetingQueryService meetingQueryService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @GetMapping("/hosts/{hostId}/meetings")
    public ResponseEntity<ApiResponse<List<MeetingSummaryResponse>>> listHostMeetings(
            @PathVariable UUID hostId,
            @RequestParam(name = "upcomingOnly", defaultValue = "true") boolean upcomingOnly,
            @RequestParam(name = "limit", required = false) Integer limit) {
        List<MeetingSummaryResponse> meetings = meetingQueryService.listHostMeetings(hostId, upcomingOnly, limit);
        return ResponseEntity.ok(ApiResponse.success(meetings));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateBookingRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        String requestHash = RequestHasher.hash(request, objectMapper);
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
                            request.hostId(),
                            request.eventTypeId(),
                            request.startTime(),
                            request.endTime(),
                            null,
                            null);
                    return new ResponseEnvelope<>(201, BookingResponse.from(saved));
                });

        return outcome.toResponseEntity(objectMapper);
    }
}
