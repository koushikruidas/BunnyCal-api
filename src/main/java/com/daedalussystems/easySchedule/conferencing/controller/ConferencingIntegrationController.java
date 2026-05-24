package com.daedalussystems.easySchedule.conferencing.controller;

import com.daedalussystems.easySchedule.calendar.auth.OAuthStateException;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingOAuthService;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingOAuthServiceRegistry;
import com.daedalussystems.easySchedule.conferencing.service.ConferencingProviderCapabilities;
import com.daedalussystems.easySchedule.integration.ProviderCapabilityRegistry;
import com.daedalussystems.easySchedule.integration.ProviderCatalogService;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/integrations/conferencing")
public class ConferencingIntegrationController {

    private final ConferencingOAuthServiceRegistry oauthRegistry;
    private final ProviderCapabilityRegistry capabilityRegistry;
    private final ProviderCatalogService providerCatalogService;
    private final String frontendErrorRedirect;
    private final String frontendSuccessRedirect;

    public ConferencingIntegrationController(ConferencingOAuthServiceRegistry oauthRegistry,
                                             ProviderCapabilityRegistry capabilityRegistry,
                                             ProviderCatalogService providerCatalogService,
                                             @Value("${zoom.oauth.frontend-error-redirect:http://localhost:5173/calendar-error}") String frontendErrorRedirect,
                                             @Value("${zoom.oauth.frontend-success-redirect:http://localhost:5173/dashboard/integrations}") String frontendSuccessRedirect) {
        this.oauthRegistry = oauthRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.providerCatalogService = providerCatalogService;
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
        Map<String, Map<String, Object>> providers = new LinkedHashMap<>();
        oauthRegistry.all().forEach((type, service) -> providers.put(type.externalId(), buildProviderEntry(service, userId)));
        payload.put("providers", providers);
        payload.put("capabilities", Map.of("conferencing", capabilityRegistry.allConferencing()));
        var catalog = providerCatalogService.catalogForUser(userId);
        payload.put("providerCatalog", providerCatalogService.conferencingProviderSubset(userId));
        payload.put("authority", Map.of(
                "conferencingProviders", catalog.authority().conferencingProviders(),
                "lifecycleAuthority", catalog.authority().lifecycleAuthority(),
                "identityProvider", catalog.authority().identityProvider()
        ));
        return ResponseEntity.ok(ApiResponse.success(payload));
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

    private static Map<String, Object> buildProviderEntry(ConferencingOAuthService service, UUID userId) {
        String status = service.status(userId);
        ConferencingProviderCapabilities capabilities = service.capabilities();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("status", status);
        entry.put("connected", "CONNECTED".equals(status));
        entry.put("type", capabilities.lifecycleType().externalId());
        entry.put("standaloneOAuth", capabilities.standaloneOAuth());
        entry.put("disconnectSupported", capabilities.standaloneDisconnect());
        entry.put("managedBy", capabilities.managedBy());
        return entry;
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
