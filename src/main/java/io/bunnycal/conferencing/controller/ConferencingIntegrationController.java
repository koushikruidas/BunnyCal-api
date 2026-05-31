package io.bunnycal.conferencing.controller;

import io.bunnycal.calendar.auth.OAuthStateException;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.conferencing.service.ConferencingOAuthService;
import io.bunnycal.conferencing.service.ConferencingOAuthServiceRegistry;
import io.bunnycal.conferencing.service.ConferencingProviderCapabilities;
import io.bunnycal.integration.ProviderCapabilityRegistry;
import io.bunnycal.integration.ProviderCatalogService;
import io.bunnycal.integration.ProviderDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/integrations/conferencing")
public class ConferencingIntegrationController {
    private static final Logger log = LoggerFactory.getLogger(ConferencingIntegrationController.class);

    // Capability-only providers that the canonical providerCatalog exposes but the
    // legacy providers map historically did not. Excluding them keeps the legacy
    // projection wire-compatible with what frontends consume today.
    private static final java.util.Set<String> LEGACY_PROVIDERS_EXCLUDED_FROM_PROJECTION =
            java.util.Set.of("custom_url");

    private final ConferencingOAuthServiceRegistry oauthRegistry;
    private final ProviderCapabilityRegistry capabilityRegistry;
    private final ProviderCatalogService providerCatalogService;
    private final MeterRegistry meterRegistry;
    private final String frontendErrorRedirect;
    private final String frontendSuccessRedirect;

    public ConferencingIntegrationController(ConferencingOAuthServiceRegistry oauthRegistry,
                                             ProviderCapabilityRegistry capabilityRegistry,
                                             ProviderCatalogService providerCatalogService,
                                             MeterRegistry meterRegistry,
                                             @Value("${zoom.oauth.frontend-error-redirect:http://localhost:5173/calendar-error}") String frontendErrorRedirect,
                                             @Value("${zoom.oauth.frontend-success-redirect:http://localhost:5173/dashboard/integrations}") String frontendSuccessRedirect) {
        this.oauthRegistry = oauthRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.providerCatalogService = providerCatalogService;
        this.meterRegistry = meterRegistry;
        this.frontendErrorRedirect = frontendErrorRedirect;
        this.frontendSuccessRedirect = frontendSuccessRedirect;
    }

    @GetMapping("/{provider}/connect")
    public ResponseEntity<ApiResponse<Map<String, String>>> connect(@PathVariable("provider") String provider,
                                                                    Authentication authentication,
                                                                    @RequestParam(value = "source", required = false) String source,
                                                                    @RequestParam(value = "returnTo", required = false) String returnTo,
                                                                    @RequestParam(value = "bookingSessionId", required = false) String bookingSessionId) {
        UUID userId = extractUserId(authentication);
        ConferencingOAuthService service = oauthRegistry.find(provider).orElse(null);
        if (service == null) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Unsupported conferencing provider"));
        }
        try {
            String redirectUrl = service.buildConnectUrl(userId, source, returnTo, bookingSessionId);
            return ResponseEntity.ok(ApiResponse.success(Map.of("redirectUrl", redirectUrl)));
        } catch (UnsupportedOperationException ex) {
            String message = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : "Conferencing provider " + service.providerType().externalId() + " does not expose a standalone OAuth flow";
            return ResponseEntity.badRequest().body(
                    ApiResponse.error(ErrorCode.VALIDATION_ERROR, message));
        }
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable("provider") String provider,
                                         @RequestParam("code") String code,
                                         @RequestParam("state") String state) {
        ConferencingOAuthService service = oauthRegistry.find(provider).orElse(null);
        if (service == null) {
            return ResponseEntity.status(302).location(errorRedirect("VALIDATION_ERROR")).build();
        }
        try {
            service.handleCallback(code, state);
            return ResponseEntity.status(302)
                    .location(URI.create(frontendSuccessRedirect + "?integrationSuccess=" + service.providerType().externalId()))
                    .build();
        } catch (UnsupportedOperationException ex) {
            return ResponseEntity.status(302).location(errorRedirect("oauth_callback_unsupported")).build();
        } catch (RuntimeException ex) {
            return ResponseEntity.status(302).location(errorRedirect(mapErrorCode(ex))).build();
        }
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        Map<String, Object> payload = new LinkedHashMap<>();
        var catalog = providerCatalogService.catalogForUser(userId);
        Map<String, ProviderDescriptor> conferencingCatalog = providerCatalogService.conferencingProviderSubset(userId);

        // providerCatalog is now the canonical source. The legacy `providers` map is a
        // strict compatibility projection of the same data: same provider state, same
        // capability flags, fewer fields. Independent construction (the old walk over
        // oauthRegistry calling .status() per service) has been removed to prevent
        // semantic drift from re-emerging — every field in the legacy entry now comes
        // from the corresponding ProviderDescriptor.
        payload.put("providers", legacyProvidersProjection(conferencingCatalog));
        payload.put("capabilities", Map.of("conferencing", capabilityRegistry.allConferencing()));
        payload.put("providerCatalog", conferencingCatalog);
        payload.put("authority", Map.of(
                "conferencingProviders", catalog.authority().conferencingProviders(),
                "lifecycleAuthority", catalog.authority().lifecycleAuthority(),
                "identityProvider", catalog.authority().identityProvider()
        ));
        // Frozen-surface signal for clients. Mirrors the pattern used by
        // /integrations/calendar/status/providers (RFC 8594-style headers) so the
        // deprecation is visible at the protocol layer, not only in docs.
        payload.put("_deprecations", Map.of(
                "providers", "Legacy provider map. Use providerCatalog instead."
        ));
        return ResponseEntity.ok()
                .header("Deprecation", "true")
                .header("Link", "</integrations/conferencing/status>; rel=\"successor-version\"; field=\"providerCatalog\"")
                .body(ApiResponse.success(payload));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<ApiResponse<Void>> disconnect(@PathVariable("provider") String provider,
                                                        Authentication authentication) {
        UUID userId = extractUserId(authentication);
        ConferencingOAuthService service = oauthRegistry.find(provider).orElse(null);
        if (service == null) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Unsupported conferencing provider"));
        }
        ConferencingProviderCapabilities capabilities = service.capabilities();
        if (!capabilities.standaloneDisconnect()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiResponse.error(ErrorCode.CONFERENCING_DISCONNECT_NOT_SUPPORTED,
                            disconnectNotSupportedMessage(service, capabilities)));
        }
        service.disconnect(userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Build the legacy {@code providers} map as a strict projection of the canonical
     * {@code providerCatalog}. Same wire shape the frontend has consumed historically
     * (status / connected / type / standaloneOAuth / disconnectSupported / managedBy),
     * derived from a single source of truth so the two surfaces cannot drift.
     *
     * <p>Capability-only providers without an OAuth service (e.g. {@code custom_url})
     * are excluded — they were never present in the legacy map and adding them now
     * would be an unintended widening on the way out.
     *
     * @deprecated The {@code providers} map is frozen. New consumers must read
     *     {@code providerCatalog}. This projection will be removed once the frontend
     *     migration is complete.
     */
    @Deprecated(forRemoval = true, since = "v1alpha-provider-catalog")
    private Map<String, Map<String, Object>> legacyProvidersProjection(Map<String, ProviderDescriptor> catalog) {
        Map<String, Map<String, Object>> projection = new LinkedHashMap<>();
        for (Map.Entry<String, ProviderDescriptor> entry : catalog.entrySet()) {
            String providerId = entry.getKey();
            if (LEGACY_PROVIDERS_EXCLUDED_FROM_PROJECTION.contains(providerId)) {
                continue;
            }
            ProviderDescriptor descriptor = entry.getValue();
            ConferencingOAuthService oauthService = resolveOAuthService(providerId);
            if (oauthService == null) {
                // Only providers that existed in the legacy registry-walk appear in the
                // projection. This is what keeps the wire shape unchanged.
                continue;
            }
            ConferencingProviderCapabilities capabilities = oauthService.capabilities();

            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("status", descriptor.status().connectionStatus());
            legacy.put("connected", descriptor.status().isConnected());
            legacy.put("type", capabilities.lifecycleType().externalId());
            legacy.put("standaloneOAuth", capabilities.standaloneOAuth());
            legacy.put("disconnectSupported", capabilities.standaloneDisconnect());
            legacy.put("managedBy", capabilities.managedBy());
            projection.put(providerId, legacy);

            log.info("legacy_provider_surface_consumed consumer=conferencing_status_endpoint provider={}", providerId);
            meterRegistry.counter("integration.conferencing.legacy_providers_surface_emitted",
                    "provider", providerId).increment();
        }
        return projection;
    }

    private ConferencingOAuthService resolveOAuthService(String providerId) {
        return oauthRegistry.find(providerId).orElse(null);
    }

    private static String disconnectNotSupportedMessage(ConferencingOAuthService service,
                                                        ConferencingProviderCapabilities capabilities) {
        String externalId = service.providerType().externalId();
        if (capabilities.managedBy() != null) {
            return externalId + " is managed by " + capabilities.managedBy()
                    + " — disconnect " + capabilities.managedBy() + " to remove it";
        }
        return externalId + " does not support standalone disconnect";
    }

    private static UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private URI errorRedirect(String code) {
        String sep = frontendErrorRedirect.contains("?") ? "&" : "?";
        String encoded = URLEncoder.encode(code, StandardCharsets.UTF_8);
        return URI.create(frontendErrorRedirect + sep + "error=" + encoded + "&code=" + encoded);
    }

    private static String mapErrorCode(RuntimeException ex) {
        if (ex instanceof OAuthStateException stateException) {
            return switch (stateException.getReason()) {
                case EXPIRED -> "oauth_state_expired";
                case MISSING_USER -> "oauth_user_missing";
                case INVALID -> "oauth_state_invalid";
            };
        }
        if (ex instanceof IllegalArgumentException) {
            return "oauth_invalid_response";
        }
        return "internal_server_error";
    }
}
