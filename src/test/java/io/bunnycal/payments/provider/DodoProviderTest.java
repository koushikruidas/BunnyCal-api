package io.bunnycal.payments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.payments.config.DodoProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DodoProvider}'s webhook verification (Standard Webhooks HMAC) and
 * raw-event → neutral {@link ProviderWebhookEvent} normalization. No network: only
 * {@code verifyWebhook} is exercised, which does not call the Dodo API.
 */
class DodoProviderTest {

    private static final String SECRET = "whsec_" + Base64.getEncoder()
            .encodeToString("super-secret-key".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DodoProvider provider = new DodoProvider(
            new DodoProperties("dodo_test_key", SECRET, true),
            objectMapper);

    @Test
    void verifiesValidSignatureAndNormalizesPaymentSucceeded() {
        String body = "{\"type\":\"payment.succeeded\",\"data\":{"
                + "\"payment_id\":\"pay_1\",\"subscription_id\":\"sub_1\","
                + "\"customer\":{\"customer_id\":\"cus_1\"},"
                + "\"total_amount\":99900,\"recurring_pre_tax_amount\":99900,\"currency\":\"INR\","
                + "\"next_billing_date\":\"2026-07-01T00:00:00Z\"}}";
        Map<String, String> headers = signedHeaders("msg_1", body);

        ProviderWebhookEvent event = provider.verifyWebhook(body.getBytes(StandardCharsets.UTF_8), headers);

        assertThat(event.type()).isEqualTo(BillingEventType.INVOICE_PAID);
        assertThat(event.rawType()).isEqualTo("payment.succeeded");
        assertThat(event.providerEventId()).isEqualTo("msg_1");
        ProviderWebhookEvent.Data d = event.data();
        assertThat(d.providerSubscriptionId()).isEqualTo("sub_1");
        assertThat(d.providerCustomerId()).isEqualTo("cus_1");
        assertThat(d.providerInvoiceId()).isEqualTo("pay_1");
        assertThat(d.totalMinor()).isEqualTo(99900);
        assertThat(d.subtotalMinor()).isEqualTo(99900);
        assertThat(d.currency()).isEqualTo("INR");
    }

    @Test
    void mapsSubscriptionActiveToCheckoutCompleted() {
        String body = "{\"type\":\"subscription.active\",\"data\":{"
                + "\"subscription_id\":\"sub_9\",\"customer\":{\"customer_id\":\"cus_9\"},"
                + "\"metadata\":{\"user_id\":\"11111111-1111-1111-1111-111111111111\"}}}";
        ProviderWebhookEvent event = provider.verifyWebhook(
                body.getBytes(StandardCharsets.UTF_8), signedHeaders("msg_9", body));

        assertThat(event.type()).isEqualTo(BillingEventType.CHECKOUT_COMPLETED);
        assertThat(event.data().providerSubscriptionId()).isEqualTo("sub_9");
        assertThat(event.data().userId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void mapsRefundSucceeded() {
        String body = "{\"type\":\"refund.succeeded\",\"data\":{"
                + "\"refund_id\":\"ref_1\",\"payment_id\":\"pay_1\",\"amount\":50000}}";
        ProviderWebhookEvent event = provider.verifyWebhook(
                body.getBytes(StandardCharsets.UTF_8), signedHeaders("msg_r", body));

        assertThat(event.type()).isEqualTo(BillingEventType.REFUND_PROCESSED);
        assertThat(event.data().providerRefundId()).isEqualTo("ref_1");
        assertThat(event.data().refundProviderInvoiceId()).isEqualTo("pay_1");
        assertThat(event.data().amountRefundedMinor()).isEqualTo(50000);
    }

    @Test
    void unknownEventTypeYieldsUnknownWithEmptyData() {
        String body = "{\"type\":\"license_key.created\",\"data\":{\"foo\":\"bar\"}}";
        ProviderWebhookEvent event = provider.verifyWebhook(
                body.getBytes(StandardCharsets.UTF_8), signedHeaders("msg_u", body));

        assertThat(event.type()).isEqualTo(BillingEventType.UNKNOWN);
        assertThat(event.data().providerSubscriptionId()).isNull();
    }

    @Test
    void rejectsTamperedSignature() {
        String body = "{\"type\":\"payment.succeeded\",\"data\":{}}";
        Map<String, String> headers = signedHeaders("msg_t", body);
        // Tamper with the body after signing.
        byte[] tampered = "{\"type\":\"payment.succeeded\",\"data\":{\"x\":1}}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> provider.verifyWebhook(tampered, headers))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void rejectsMissingHeaders() {
        String body = "{\"type\":\"payment.succeeded\",\"data\":{}}";
        assertThatThrownBy(() -> provider.verifyWebhook(
                body.getBytes(StandardCharsets.UTF_8), new HashMap<>()))
                .isInstanceOf(WebhookVerificationException.class);
    }

    /** Builds a valid Standard-Webhooks header set for the given id + body. */
    private static Map<String, String> signedHeaders(String id, String body) {
        String timestamp = "1700000000";
        byte[] key = Base64.getDecoder().decode(SECRET.substring("whsec_".length()));
        String signedContent = id + "." + timestamp + "." + body;
        String sig = base64HmacSha256(key, signedContent);
        Map<String, String> headers = new HashMap<>();
        headers.put("webhook-id", id);
        headers.put("webhook-timestamp", timestamp);
        headers.put("webhook-signature", "v1," + sig);
        return headers;
    }

    private static String base64HmacSha256(byte[] key, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
