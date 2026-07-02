package io.bunnycal.conferencing.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.conferencing.service.ZoomConferencingOAuthService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ZoomWebhookControllerTest {
    private static final String SECRET = "test-secret-token";

    @Mock
    private ZoomConferencingOAuthService zoomOAuthService;

    private ZoomWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new ZoomWebhookController(zoomOAuthService, new ObjectMapper(), SECRET);
    }

    @Test
    void urlValidation_echoesPlainTokenWithHmac() {
        String body = "{\"event\":\"endpoint.url_validation\",\"payload\":{\"plainToken\":\"abc123\"}}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        ResponseEntity<Map<String, String>> response = controller.handle(body, sign(body, ts), ts);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("abc123", response.getBody().get("plainToken"));
        assertEquals(hmacHex(SECRET, "abc123"), response.getBody().get("encryptedToken"));
    }

    @Test
    void deauthorized_deletesConnectionForZoomUser() {
        String body = "{\"event\":\"app_deauthorized\",\"payload\":{\"user_id\":\"zoom-user-1\",\"account_id\":\"acc\"}}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        ResponseEntity<Map<String, String>> response = controller.handle(body, sign(body, ts), ts);

        assertEquals(200, response.getStatusCode().value());
        verify(zoomOAuthService).handleDeauthorized("zoom-user-1");
    }

    @Test
    void invalidSignature_isRejectedWithoutSideEffects() {
        String body = "{\"event\":\"app_deauthorized\",\"payload\":{\"user_id\":\"zoom-user-1\"}}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        ResponseEntity<Map<String, String>> response = controller.handle(body, "v0=deadbeef", ts);

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(zoomOAuthService);
    }

    @Test
    void staleTimestamp_isRejected() {
        String body = "{\"event\":\"app_deauthorized\",\"payload\":{\"user_id\":\"zoom-user-1\"}}";
        String ts = String.valueOf(Instant.now().getEpochSecond() - 3600);

        ResponseEntity<Map<String, String>> response = controller.handle(body, sign(body, ts), ts);

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(zoomOAuthService);
    }

    @Test
    void millisecondTimestamp_isAccepted() {
        String body = "{\"event\":\"other.event\"}";
        String ts = String.valueOf(Instant.now().toEpochMilli());

        ResponseEntity<Map<String, String>> response = controller.handle(body, sign(body, ts), ts);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void missingSecretConfig_rejectsAllRequests() {
        ZoomWebhookController unconfigured =
                new ZoomWebhookController(zoomOAuthService, new ObjectMapper(), "");
        String body = "{\"event\":\"app_deauthorized\",\"payload\":{\"user_id\":\"u\"}}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        ResponseEntity<Map<String, String>> response = unconfigured.handle(body, sign(body, ts), ts);

        assertEquals(401, response.getStatusCode().value());
        verify(zoomOAuthService, never()).handleDeauthorized("u");
    }

    private static String sign(String body, String timestamp) {
        return "v0=" + hmacHex(SECRET, "v0:" + timestamp + ":" + body);
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
