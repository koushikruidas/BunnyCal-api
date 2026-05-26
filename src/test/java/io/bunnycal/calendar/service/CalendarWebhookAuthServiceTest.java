package io.bunnycal.calendar.service;

import io.bunnycal.calendar.dto.GoogleWebhookRequest;
import io.bunnycal.common.exception.CustomException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalendarWebhookAuthServiceTest {

    @Test
    void legacySecretMode_acceptsMatchingSharedSecret() {
        CalendarWebhookAuthService service = new CalendarWebhookAuthService(new SimpleMeterRegistry(), false, 300);
        GoogleWebhookRequest request = new GoogleWebhookRequest(UUID.randomUUID(), "evt-1", "{}");
        assertDoesNotThrow(() -> service.verifyGoogle("secret", "secret", null, null, request));
    }

    @Test
    void hmacMode_rejectsInvalidSignature() {
        CalendarWebhookAuthService service = new CalendarWebhookAuthService(new SimpleMeterRegistry(), true, 300);
        GoogleWebhookRequest request = new GoogleWebhookRequest(UUID.randomUUID(), "evt-1", "{}");
        long now = System.currentTimeMillis() / 1000;
        assertThrows(CustomException.class,
                () -> service.verifyGoogle("secret", null, "bad", String.valueOf(now), request));
    }

    @Test
    void hmacMode_acceptsValidSignature() throws Exception {
        CalendarWebhookAuthService service = new CalendarWebhookAuthService(new SimpleMeterRegistry(), true, 300);
        GoogleWebhookRequest request = new GoogleWebhookRequest(UUID.fromString("00000000-0000-0000-0000-000000000001"), "evt-1", "{\"id\":\"evt-1\"}");
        long now = System.currentTimeMillis() / 1000;
        String payload = now + "." + request.connectionId() + "." + request.providerEventId() + "." + request.rawPayload();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

        assertDoesNotThrow(() -> service.verifyGoogle("secret", null, sig, String.valueOf(now), request));
    }

    @Test
    void googleWatchMode_acceptsMatchingChannelToken() {
        CalendarWebhookAuthService service = new CalendarWebhookAuthService(new SimpleMeterRegistry(), true, 300);
        assertDoesNotThrow(() -> service.verifyGoogleWatchNotification("secret", "secret"));
    }
}
