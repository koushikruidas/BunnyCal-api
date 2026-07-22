package io.bunnycal.hostpayments.controller;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.dto.PaymentConnectionResponse;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.service.PaymentConnectionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment-connections")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class PaymentConnectionController {
    private final PaymentConnectionService service;
    private final HostPaymentProviderRegistry providers;

    public PaymentConnectionController(PaymentConnectionService service, HostPaymentProviderRegistry providers) {
        this.service = service;
        this.providers = providers;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PaymentConnectionResponse>>> list(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(service.list(userId(auth))));
    }

    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<List<String>>> providers() {
        return ResponseEntity.ok(ApiResponse.success(providers.availableTypes().stream().map(Enum::name).sorted().toList()));
    }

    @PostMapping("/{providerName}/onboarding")
    public ResponseEntity<ApiResponse<Map<String, String>>> onboard(Authentication auth,
                                                                    @PathVariable String providerName) {
        PaymentProviderType provider;
        try { provider = PaymentProviderType.valueOf(providerName.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Unsupported payment provider.");
        }
        if (!providers.availableTypes().contains(provider)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Payment provider is not configured.");
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "redirectUrl", service.startOnboarding(userId(auth), provider))));
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<ApiResponse<PaymentConnectionResponse>> refresh(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.refresh(userId(auth), id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> disconnect(Authentication auth, @PathVariable UUID id) {
        service.disconnect(userId(auth), id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) throw new CustomException(ErrorCode.UNAUTHORIZED);
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID id) return id;
        try { return UUID.fromString(principal.toString()); }
        catch (IllegalArgumentException e) { throw new CustomException(ErrorCode.UNAUTHORIZED); }
    }
}
