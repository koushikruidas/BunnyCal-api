package io.bunnycal.hostpayments.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.booking.idempotency.IdempotencyOutcome;
import io.bunnycal.booking.idempotency.IdempotencyRoutes;
import io.bunnycal.booking.idempotency.IdempotencyService;
import io.bunnycal.booking.idempotency.ResponseEnvelope;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.util.RequestHasher;
import io.bunnycal.hostpayments.dto.PaymentInitializationResponse;
import io.bunnycal.hostpayments.dto.PublicPaymentStatusResponse;
import io.bunnycal.hostpayments.service.HostPaymentLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/{username}/{eventTypeSlug}/book/{reservationId}/payment")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class PublicHostPaymentController {
    private final HostPaymentLifecycleService service;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PublicHostPaymentController(HostPaymentLifecycleService service,
                                       IdempotencyService idempotencyService,
                                       ObjectMapper objectMapper) {
        this.service = service;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> initialize(
            @PathVariable String username, @PathVariable String eventTypeSlug,
            @PathVariable UUID reservationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String requestHash = RequestHasher.hash(Map.of(
                "username", username,
                "eventTypeSlug", eventTypeSlug,
                "reservationId", reservationId), objectMapper);
        UUID routeScope = UUID.nameUUIDFromBytes(
                (username + ":" + eventTypeSlug).getBytes(StandardCharsets.UTF_8));
        IdempotencyOutcome outcome = idempotencyService.execute(
                idempotencyKey,
                routeScope,
                IdempotencyRoutes.PUBLIC_BOOK_PAYMENT,
                requestHash,
                () -> new ResponseEnvelope<>(200,
                        ApiResponse.success(service.initialize(username, eventTypeSlug, reservationId))));
        return outcome.toResponseEntity(objectMapper);
    }

    @PostMapping("/finalize")
    public ResponseEntity<ApiResponse<PublicPaymentStatusResponse>> finalizePayment(
            @PathVariable String username, @PathVariable String eventTypeSlug,
            @PathVariable UUID reservationId) {
        return ResponseEntity.ok(ApiResponse.success(service.finalizePayment(username, eventTypeSlug, reservationId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PublicPaymentStatusResponse>> status(
            @PathVariable String username, @PathVariable String eventTypeSlug,
            @PathVariable UUID reservationId) {
        return ResponseEntity.ok(ApiResponse.success(service.status(username, eventTypeSlug, reservationId)));
    }
}
