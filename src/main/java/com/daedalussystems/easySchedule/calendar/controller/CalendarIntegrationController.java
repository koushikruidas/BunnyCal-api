package com.daedalussystems.easySchedule.calendar.controller;

import com.daedalussystems.easySchedule.calendar.service.CalendarOAuthService;
import com.daedalussystems.easySchedule.calendar.service.CalendarWebhookAuthService;
import com.daedalussystems.easySchedule.calendar.service.CalendarWebhookIngestionService;
import com.daedalussystems.easySchedule.calendar.replay.WebhookDeliveryMetadata;
import com.daedalussystems.easySchedule.calendar.config.GoogleOAuthProperties;
import com.daedalussystems.easySchedule.calendar.dto.GoogleWebhookRequest;
import com.daedalussystems.easySchedule.calendar.auth.OAuthStateException;
import com.daedalussystems.easySchedule.common.api.ApiResponse;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.MDC;
import com.daedalussystems.easySchedule.sync.state.SyncSourceAttribution;

@RestController
@RequestMapping("/integrations/calendar")
public class CalendarIntegrationController {
    private static final Logger log = LoggerFactory.getLogger(CalendarIntegrationController.class);
    private static final String GOOGLE_PROVIDER = "google";
    private static final String DASHBOARD_FALLBACK_RETURN_TO = "/dashboard/integrations";
    private static final String PUBLIC_FALLBACK_RETURN_TO = "/";
    private final CalendarOAuthService oauthService;
    private final CalendarWebhookAuthService webhookAuthService;
    private final CalendarWebhookIngestionService webhookIngestionService;
    private final GoogleOAuthProperties googleOAuthProperties;
    private final String webhookSharedSecret;

    public CalendarIntegrationController(CalendarOAuthService oauthService,
                                         CalendarWebhookAuthService webhookAuthService,
                                         CalendarWebhookIngestionService webhookIngestionService,
                                         GoogleOAuthProperties googleOAuthProperties,
                                         @Value("${calendar.webhook.shared-secret:}") String webhookSharedSecret) {
        this.oauthService = oauthService;
        this.webhookAuthService = webhookAuthService;
        this.webhookIngestionService = webhookIngestionService;
        this.googleOAuthProperties = googleOAuthProperties;
        this.webhookSharedSecret = webhookSharedSecret;
    }

    @GetMapping("/google/connect")
    public ResponseEntity<ApiResponse<Map<String, String>>> connectGoogle(Authentication authentication,
                                                                          @RequestParam(value = "source", required = false) String source,
                                                                          @RequestParam(value = "returnTo", required = false) String returnTo,
                                                                          @RequestParam(value = "bookingSessionId", required = false)
                                                                          String bookingSessionId) {
        UUID userId = extractUserId(authentication);
        String redirectUrl = oauthService.buildGoogleConnectUrl(userId, source, normalizeReturnTo(returnTo), bookingSessionId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("redirectUrl", redirectUrl)));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<Void> callbackGoogle(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {

        log.info("OAuth callback received: codePresent={}, statePresent={}",
                code != null && !code.isBlank(),
                state != null && !state.isBlank());

        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            log.warn("OAuth callback validation failed: missing code/state");
            return ResponseEntity.status(302)
                    .location(errorRedirect("VALIDATION_ERROR"))
                    .build();
        }

        try {
            log.info("Processing Google OAuth callback...");
            MDC.put("oauthCorrelationId", Integer.toHexString(state.hashCode()));

            CalendarOAuthService.OAuthCallbackResult result = oauthService.handleGoogleCallback(code, state);

            log.info("OAuth callback SUCCESS");

            return ResponseEntity.status(302)
                    .location(resolveSuccessRedirect(result))
                    .build();

        } catch (RuntimeException ex) {
            String errorCode = mapErrorCode(ex);

            // 🔥 THIS is the critical part
            log.error("OAuth callback FAILED: errorCode={}, message={}",
                    errorCode,
                    ex.getMessage(),
                    ex); // <-- prints full stacktrace

            return ResponseEntity.status(302)
                    .location(errorRedirect(errorCode))
                    .build();
        } finally {
            MDC.remove("oauthCorrelationId");
        }
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, String>>> status(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.success(Map.of("google", oauthService.googleConnectionStatus(userId))));
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<ApiResponse<Void>> disconnect(@PathVariable("provider") String provider,
                                                        Authentication authentication) {
        UUID userId = extractUserId(authentication);
        if (!GOOGLE_PROVIDER.equalsIgnoreCase(provider)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Unsupported provider"));
        }
        oauthService.disconnectGoogle(userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/webhooks/google")
    public ResponseEntity<ApiResponse<Void>> ingestGoogleWebhook(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String webhookSecret,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String webhookSignature,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String webhookTimestamp,
            @RequestHeader(value = "X-Webhook-Delivery-Id", required = false) String deliveryId,
            @RequestHeader(value = "X-Goog-Channel-Id", required = false) String googleChannelId,
            @RequestHeader(value = "X-Goog-Channel-Token", required = false) String googleChannelToken,
            @RequestHeader(value = "X-Goog-Message-Number", required = false) String googleMessageNumber,
            @RequestHeader(value = "X-Goog-Resource-Id", required = false) String googleResourceId,
            @RequestHeader(value = "X-Goog-Resource-State", required = false) String googleResourceState,
            @RequestHeader(value = "X-Provider-Updated-At", required = false) String providerUpdatedAtHeader,
            @RequestHeader(value = "X-Provider-Etag", required = false) String providerEtag,
            @RequestHeader(value = "X-Provider-Sequence", required = false) String providerSequenceHeader,
            @RequestBody(required = false) GoogleWebhookRequest request) {
        boolean isGooglePushNotification = googleChannelId != null && !googleChannelId.isBlank();
        UUID resolvedConnectionId = request == null ? null : request.connectionId();
        String resolvedProviderEventId = request == null ? null : request.providerEventId();
        String resolvedRawPayload = request == null ? null : request.rawPayload();
        if (isGooglePushNotification) {
            webhookAuthService.verifyGoogleWatchNotification(webhookSharedSecret, googleChannelToken);
            resolvedConnectionId = oauthService.findGoogleConnectionIdByWebhookChannel(googleChannelId)
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Calendar connection for Google channel not found."));
            if (resolvedProviderEventId == null || resolvedProviderEventId.isBlank()) {
                resolvedProviderEventId = "google_channel_signal";
            }
            if (resolvedRawPayload == null) {
                resolvedRawPayload = "";
            }
            resolvedRawPayload = resolvedRawPayload
                    + "|goog_message_number=" + (googleMessageNumber == null ? "" : googleMessageNumber)
                    + "|goog_resource_state=" + (googleResourceState == null ? "" : googleResourceState)
                    + "|goog_resource_id=" + (googleResourceId == null ? "" : googleResourceId);
        } else {
            if (request == null) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR, "Webhook payload is required.");
            }
            webhookAuthService.verifyGoogle(
                    webhookSharedSecret,
                    webhookSecret,
                    webhookSignature,
                    webhookTimestamp,
                    request);
        }
        log.info("webhook_received provider=google connectionId={} providerEventId={} deliveryId={} providerSequence={} providerUpdatedAt={} payloadPresent={}",
                resolvedConnectionId,
                resolvedProviderEventId,
                deliveryId,
                providerSequenceHeader,
                providerUpdatedAtHeader,
                resolvedRawPayload != null && !resolvedRawPayload.isBlank());
        String fallbackDeliveryId = isGooglePushNotification && googleMessageNumber != null && !googleMessageNumber.isBlank()
                ? "goog-msg-" + googleMessageNumber
                : null;
        String correlationId = deliveryId == null || deliveryId.isBlank()
                ? (fallbackDeliveryId == null ? UUID.randomUUID().toString() : fallbackDeliveryId)
                : deliveryId;
        MDC.put("correlationId", correlationId);
        MDC.put("causationId", resolvedProviderEventId == null ? "" : resolvedProviderEventId);
        try {
            webhookIngestionService.ingestGoogle(
                    resolvedConnectionId,
                    resolvedProviderEventId,
                    resolvedRawPayload,
                    new WebhookDeliveryMetadata(
                            parseInstantOrNull(providerUpdatedAtHeader),
                            providerEtag,
                            parseLongOrNull(providerSequenceHeader),
                            deliveryId,
                            SyncSourceAttribution.WEBHOOK)
            );
        } finally {
            MDC.remove("causationId");
            MDC.remove("correlationId");
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    ResponseEntity<ApiResponse<Void>> ingestGoogleWebhook(
            String webhookSecret,
            GoogleWebhookRequest request) {
        return ingestGoogleWebhook(
                webhookSecret, null, null, null,
                null, null, null, null, null,
                null, null, null, request);
    }

    private UUID extractUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new IllegalStateException("Authenticated user id is required");
        }
        return userId;
    }

    private URI errorRedirect(String code) {
        String sep = googleOAuthProperties.getFrontendErrorRedirect().contains("?") ? "&" : "?";
        String encoded = encode(code);
        return URI.create(googleOAuthProperties.getFrontendErrorRedirect() + sep + "error=" + encoded + "&code=" + encoded);
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

    private URI resolveSuccessRedirect(CalendarOAuthService.OAuthCallbackResult result) {
        URI success = URI.create(googleOAuthProperties.getFrontendSuccessRedirect());
        String origin = success.getScheme() + "://" + success.getAuthority();

        String returnTo = resolveReturnTo(result);
        String target = appendQueryParam(returnTo, "integrationSuccess", GOOGLE_PROVIDER);
        return URI.create(origin + target);
    }

    private static String resolveReturnTo(CalendarOAuthService.OAuthCallbackResult result) {
        if (result != null && result.returnTo() != null && !result.returnTo().isBlank()) {
            return result.returnTo();
        }
        if (result != null && "public-booking".equals(result.source())) {
            return PUBLIC_FALLBACK_RETURN_TO;
        }
        return DASHBOARD_FALLBACK_RETURN_TO;
    }

    private static String appendQueryParam(String pathWithQueryAndHash, String key, String value) {
        int hashIndex = pathWithQueryAndHash.indexOf('#');
        String pathAndQuery = hashIndex >= 0 ? pathWithQueryAndHash.substring(0, hashIndex) : pathWithQueryAndHash;
        String hash = hashIndex >= 0 ? pathWithQueryAndHash.substring(hashIndex) : "";
        String separator = pathAndQuery.contains("?") ? "&" : "?";
        return pathAndQuery + separator + encode(key) + "=" + encode(value) + hash;
    }

    private static String encode(String input) {
        return URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    private static String normalizeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return null;
        }
        if (!returnTo.startsWith("/") || returnTo.startsWith("//")) {
            throw new IllegalArgumentException("returnTo must be a relative path");
        }
        return returnTo;
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
