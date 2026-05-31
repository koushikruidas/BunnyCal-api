package io.bunnycal.calendar.service;

import io.bunnycal.calendar.dto.GoogleWebhookRequest;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CalendarWebhookAuthService {
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final boolean requireSignature;
    private final long maxSkewSeconds;

    public CalendarWebhookAuthService(MeterRegistry meterRegistry,
                                      @Value("${calendar.webhook.auth.require-signature:true}") boolean requireSignature,
                                      @Value("${calendar.webhook.auth.max-skew-seconds:300}") long maxSkewSeconds) {
        this.meterRegistry = meterRegistry;
        this.clock = Clock.systemUTC();
        this.requireSignature = requireSignature;
        this.maxSkewSeconds = Math.max(30L, maxSkewSeconds);
    }

    public void verifyGoogle(String configuredSecret,
                             String legacySecretHeader,
                             String signatureHeader,
                             String timestampHeader,
                             GoogleWebhookRequest request) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            reject("missing_secret_config", ErrorCode.FORBIDDEN, "Webhook shared secret is not configured.");
        }
        if (!requireSignature) {
            if (!configuredSecret.equals(legacySecretHeader)) {
                reject("legacy_secret_mismatch", ErrorCode.UNAUTHORIZED, "Invalid webhook secret.");
            }
            meterRegistry.counter("calendar.webhook.auth.success.total", "mode", "legacy_secret").increment();
            return;
        }

        if (signatureHeader == null || signatureHeader.isBlank() || timestampHeader == null || timestampHeader.isBlank()) {
            reject("signature_headers_missing", ErrorCode.UNAUTHORIZED, "Missing webhook signature headers.");
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestampHeader);
        } catch (NumberFormatException ex) {
            reject("timestamp_invalid", ErrorCode.UNAUTHORIZED, "Invalid webhook signature timestamp.");
            return;
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - epochSeconds) > maxSkewSeconds) {
            reject("timestamp_skew", ErrorCode.UNAUTHORIZED, "Webhook signature timestamp expired.");
        }

        String payload = canonicalPayload(epochSeconds, request);
        String expected = hmacSha256Hex(configuredSecret, payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            reject("signature_mismatch", ErrorCode.UNAUTHORIZED, "Invalid webhook signature.");
        }
        meterRegistry.counter("calendar.webhook.auth.success.total", "mode", "hmac").increment();
    }

    public void verifyGoogleWatchNotification(String configuredSecret, String channelTokenHeader) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            reject("missing_secret_config", ErrorCode.FORBIDDEN, "Webhook shared secret is not configured.");
        }
        if (channelTokenHeader == null || channelTokenHeader.isBlank()) {
            reject("channel_token_missing", ErrorCode.UNAUTHORIZED, "Missing Google webhook channel token.");
        }
        if (!configuredSecret.equals(channelTokenHeader)) {
            reject("channel_token_mismatch", ErrorCode.UNAUTHORIZED, "Invalid Google webhook channel token.");
        }
        meterRegistry.counter("calendar.webhook.auth.success.total", "mode", "google_watch").increment();
    }

    public void verifyMicrosoftNotification(String configuredSecret, String clientState) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            reject("missing_secret_config", ErrorCode.FORBIDDEN, "Webhook shared secret is not configured.");
        }
        if (clientState == null || clientState.isBlank()) {
            reject("microsoft_client_state_missing", ErrorCode.UNAUTHORIZED, "Missing Microsoft webhook client state.");
        }
        if (!configuredSecret.equals(clientState)) {
            reject("microsoft_client_state_mismatch", ErrorCode.UNAUTHORIZED, "Invalid Microsoft webhook client state.");
        }
        meterRegistry.counter("calendar.webhook.auth.success.total", "mode", "microsoft_client_state").increment();
    }

    private void reject(String reason, ErrorCode code, String message) {
        meterRegistry.counter("calendar.webhook.auth.failure.total", "reason", reason).increment();
        throw new CustomException(code, message);
    }

    private static String canonicalPayload(long epochSeconds, GoogleWebhookRequest request) {
        String connectionId = request == null || request.connectionId() == null ? "" : request.connectionId().toString();
        String eventId = request == null || request.providerEventId() == null ? "" : request.providerEventId();
        String raw = request == null || request.rawPayload() == null ? "" : request.rawPayload();
        return epochSeconds + "." + connectionId + "." + eventId + "." + raw;
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to verify webhook signature", ex);
        }
    }
}
