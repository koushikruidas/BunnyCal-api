package com.daedalussystems.easySchedule.booking.controller;

import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.dto.BookingResponse;
import com.daedalussystems.easySchedule.booking.dto.CreateBookingRequest;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyOutcome;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyService;
import com.daedalussystems.easySchedule.booking.idempotency.ResponseEnvelope;
import com.daedalussystems.easySchedule.booking.service.BookingService;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.util.RequestHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private static final String ROUTE = "POST /api/bookings";

    private final BookingService bookingService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateBookingRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        String requestHash = RequestHasher.hash(request, objectMapper);
        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                request.userId(),
                ROUTE,
                requestHash,
                () -> {
                    Booking saved = bookingService.createBooking(
                            request.userId(),
                            request.eventTypeId(),
                            request.startTime(),
                            request.endTime());
                    return new ResponseEnvelope<>(201, BookingResponse.from(saved));
                });

        return outcome.toResponseEntity(objectMapper);
    }
}
