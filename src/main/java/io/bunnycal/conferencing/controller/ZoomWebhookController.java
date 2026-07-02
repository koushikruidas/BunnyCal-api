package io.bunnycal.conferencing.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives Zoom marketplace webhook events. Two events matter for compliance:
 *
 * <ul>
 *   <li>{@code endpoint.url_validation} — Zoom's challenge when the endpoint URL is
 *       saved in the marketplace app config; must echo the plain token plus an
 *       HMAC-SHA256 of it keyed with the app's Secret Token.</li>
 *   <li>{@code app_deauthorized} — the user removed the app from their Zoom account;
 *       all stored data for that Zoom user must be deleted within 10 days.</li>
 * </ul>
 *
 * Every request is authenticated by the {@code x-zm-signature} header
 * (HMAC-SHA256 of {@code v0:<timestamp>:<body>} with the Secret Token), not JWT,
 * so the path is permitted in SecurityConfig the same way billing webhooks are.
 */
@RestController
@RequestMapping("/integrations/conferencing/zoom/webhooks")
public class ZoomWebhookController {
    private static final Logger log = LoggerFactory.getLogger(ZoomWebhookController.class);
    private static final long MAX_TIMESTAMP_SKEW_SECONDS = 300;

    private final ZoomConferencingOAuthService zoomOAuthService;
    private final ObjectMapper objectMapper;
    private final String secretToken;

    public ZoomWebhookController(ZoomConferencingOAuthService zoomOAuthService,
                                 ObjectMapper objectMapper,
                                 @Value("${zoom.oauth.webhook-secret-token:}") String secretToken) {
        this.zoomOAuthService = zoomOAuthService;
        this.objectMapper = objectMapper;
        this.secretToken = secretToken;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> handle(@RequestBody String body,
                                                      @RequestHeader(value = "x-zm-signature", required = false) String signature,
                                                      @RequestHeader(value = "x-zm-request-timestamp", required = false) String timestamp) {
        if (secretToken == null || secretToken.isBlank()) {
            log.warn("zoom_webhook_rejected reason=secret_token_not_configured");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!signatureValid(body, signature, timestamp)) {
            log.warn("zoom_webhook_rejected reason=invalid_signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            return ResponseEntity.badRequest().build();
        }

        String event = root.path("event").asText("");
        switch (event) {
            case "endpoint.url_validation" -> {
                String plainToken = root.path("payload").path("plainToken").asText("");
                return ResponseEntity.ok(Map.of(
                        "plainToken", plainToken,
                        "encryptedToken", hmacHex(plainToken)));
            }
            case "app_deauthorized" -> {
                String providerUserId = root.path("payload").path("user_id").asText("");
                zoomOAuthService.handleDeauthorized(providerUserId);
                log.info("zoom_webhook_deauthorized handled=true");
                return ResponseEntity.ok().build();
            }
            default -> {
                log.info("zoom_webhook_ignored event={}", event);
                return ResponseEntity.ok().build();
            }
        }
    }

    private boolean signatureValid(String body, String signature, String timestamp) {
        if (signature == null || signature.isBlank() || timestamp == null || timestamp.isBlank()) {
            return false;
        }
        if (!timestampFresh(timestamp)) {
            return false;
        }
        String expected = "v0=" + hmacHex("v0:" + timestamp + ":" + body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /** Replay protection: reject events whose timestamp is outside the allowed skew. */
    private static boolean timestampFresh(String timestamp) {
        try {
            long value = Long.parseLong(timestamp.trim());
            // Zoom has sent this header in both seconds and milliseconds historically.
            long epochSeconds = value > 1_000_000_000_000L ? value / 1000 : value;
            return Math.abs(Instant.now().getEpochSecond() - epochSeconds) <= MAX_TIMESTAMP_SKEW_SECONDS;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private String hmacHex(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }
}
