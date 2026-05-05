package com.daedalussystems.easySchedule.booking.controller;

import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
import com.daedalussystems.easySchedule.booking.dto.BookingResponse;
import com.daedalussystems.easySchedule.booking.dto.PublicBookRequest;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyOutcome;
import com.daedalussystems.easySchedule.booking.idempotency.IdempotencyService;
import com.daedalussystems.easySchedule.booking.idempotency.ResponseEnvelope;
import com.daedalussystems.easySchedule.booking.service.PublicBookingService;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.common.util.RequestHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
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
    private final PublicBookingService publicBookingService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PublicBookingController(PublicBookingService publicBookingService,
                                   IdempotencyService idempotencyService,
                                   ObjectMapper objectMapper) {
        this.publicBookingService = publicBookingService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
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

    @PostMapping("/{username}/{eventTypeSlug}/book")
    public ResponseEntity<?> hold(@PathVariable String username,
                                  @PathVariable String eventTypeSlug,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @RequestBody PublicBookRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String route = "POST /public/" + username + "/" + eventTypeSlug + "/book";
        String requestHash = RequestHasher.hash(Map.of(
                "username", username,
                "eventTypeSlug", eventTypeSlug,
                "startTime", request.startTime()
        ), objectMapper);

        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                routeScopeUser(username, eventTypeSlug),
                route,
                requestHash,
                () -> new ResponseEnvelope<>(201, publicBookingService.hold(username, eventTypeSlug, request))
        );

        return outcome.toResponseEntity(objectMapper);
    }

    @PostMapping("/{username}/{eventTypeSlug}/book/{bookingId}/confirm")
    public ResponseEntity<ApiResponse<Void>> confirm(@PathVariable String username,
                                                     @PathVariable String eventTypeSlug,
                                                     @PathVariable String bookingId) {
        publicBookingService.confirm(username, eventTypeSlug, java.util.UUID.fromString(bookingId));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private static java.util.UUID routeScopeUser(String username, String eventTypeSlug) {
        return java.util.UUID.nameUUIDFromBytes((username + ":" + eventTypeSlug).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
