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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Host-direct payment connections.
 *
 * <p><b>This controller registers whether or not commerce is enabled</b>, unlike the machinery behind
 * it ({@link PaymentConnectionService}, {@link HostPaymentProviderRegistry} and the rest of
 * {@code io.bunnycal.hostpayments}), which stays {@code @ConditionalOnProperty("commerce.enabled")}.
 *
 * <p>It used to carry that annotation too. With commerce off the bean never registered, Spring fell
 * through to static-resource handling, and every client call raised {@code NoResourceFoundException}
 * — logged at ERROR by the global handler. The dashboard and event editor call these endpoints on
 * load to decide whether to show payment UI, so a deliberately-disabled feature produced a steady
 * drip of ERROR-level noise, and "commerce is off" was something the frontend could only learn by
 * catching a failure.
 *
 * <p>Commerce-off is a valid deployment state, so it gets a valid answer instead of an exception:
 * the read endpoints report an empty catalog, and the write endpoints reject explicitly. Nothing is
 * silenced — a genuinely broken route still 404s, and an attempt to onboard while commerce is off
 * still fails loudly with a reason.
 */
@RestController
@RequestMapping("/api/payment-connections")
public class PaymentConnectionController {
    private final ObjectProvider<PaymentConnectionService> serviceProvider;
    private final ObjectProvider<HostPaymentProviderRegistry> providersProvider;

    public PaymentConnectionController(ObjectProvider<PaymentConnectionService> serviceProvider,
                                       ObjectProvider<HostPaymentProviderRegistry> providersProvider) {
        this.serviceProvider = serviceProvider;
        this.providersProvider = providersProvider;
    }

    /** True when {@code commerce.enabled} left the host-payment beans in the context. */
    private boolean commerceEnabled() {
        return serviceProvider.getIfAvailable() != null && providersProvider.getIfAvailable() != null;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PaymentConnectionResponse>>> list(Authentication auth) {
        PaymentConnectionService service = serviceProvider.getIfAvailable();
        if (service == null) {
            // No commerce, therefore no connections. An empty list is the truthful answer, and the
            // clients already treat it as "no payment UI".
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        return ResponseEntity.ok(ApiResponse.success(service.list(userId(auth))));
    }

    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<List<String>>> providers() {
        HostPaymentProviderRegistry providers = providersProvider.getIfAvailable();
        if (providers == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        return ResponseEntity.ok(ApiResponse.success(providers.availableTypes().stream().map(Enum::name).sorted().toList()));
    }

    @PostMapping("/{providerName}/onboarding")
    public ResponseEntity<ApiResponse<Map<String, String>>> onboard(Authentication auth,
                                                                    @PathVariable String providerName) {
        requireCommerce();
        HostPaymentProviderRegistry providers = providersProvider.getObject();
        PaymentProviderType provider;
        try { provider = PaymentProviderType.valueOf(providerName.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Unsupported payment provider.");
        }
        if (!providers.availableTypes().contains(provider)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Payment provider is not configured.");
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "redirectUrl", serviceProvider.getObject().startOnboarding(userId(auth), provider))));
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<ApiResponse<PaymentConnectionResponse>> refresh(Authentication auth, @PathVariable UUID id) {
        requireCommerce();
        return ResponseEntity.ok(ApiResponse.success(serviceProvider.getObject().refresh(userId(auth), id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> disconnect(Authentication auth, @PathVariable UUID id) {
        requireCommerce();
        serviceProvider.getObject().disconnect(userId(auth), id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Changing payment state while commerce is disabled is a real error, so it says so rather than
     * pretending to succeed.
     */
    private void requireCommerce() {
        if (!commerceEnabled()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Payments are not enabled on this deployment.");
        }
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) throw new CustomException(ErrorCode.UNAUTHORIZED);
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID id) return id;
        try { return UUID.fromString(principal.toString()); }
        catch (IllegalArgumentException e) { throw new CustomException(ErrorCode.UNAUTHORIZED); }
    }
}
