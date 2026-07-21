package io.bunnycal.hostpayments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.hostpayments.config.HostCommerceProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class StripeHostPaymentProviderWebhookTest {
    private static final String SECRET = "whsec_offline_contract_test";

    @Test
    void verifiesLocallySignedConnectedAccountWebhookAndExtractsPaymentIntent() throws Exception {
        StripeHostPaymentProvider provider = provider();
        String payload = """
                {
                  "id":"evt_offline_123",
                  "object":"event",
                  "api_version":"2025-06-30.basil",
                  "created":1760000000,
                  "account":"acct_host_123",
                  "type":"payment_intent.succeeded",
                  "data":{"object":{
                    "id":"pi_offline_123",
                    "object":"payment_intent",
                    "amount":2500,
                    "currency":"usd",
                    "status":"succeeded",
                    "metadata":{"bunnycal_payment_id":"00000000-0000-0000-0000-000000000001"}
                  }}
                }
                """;
        long timestamp = Instant.now().getEpochSecond();

        HostPaymentProvider.VerifiedWebhook event = provider.verifyWebhook(
                payload.getBytes(StandardCharsets.UTF_8), signature(timestamp, payload, SECRET));

        assertThat(event.eventId()).isEqualTo("evt_offline_123");
        assertThat(event.eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(event.providerAccountId()).isEqualTo("acct_host_123");
        assertThat(event.providerPaymentId()).isEqualTo("pi_offline_123");
        assertThat(event.rawPayload()).isEqualTo(payload);
    }

    @Test
    void rejectsPayloadWhenSignatureDoesNotMatch() {
        StripeHostPaymentProvider provider = provider();
        byte[] payload = "{\"id\":\"evt_tampered\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> provider.verifyWebhook(payload, "t=1,v1=invalid"))
                .isInstanceOf(HostPaymentProviderException.class);
    }

    private static StripeHostPaymentProvider provider() {
        return new StripeHostPaymentProvider(new HostCommerceProperties(
                true,
                "http://localhost:5173",
                new HostCommerceProperties.Stripe(
                        "sk_test_offline", "pk_test_offline", SECRET,
                        "http://localhost:5173/payment/return",
                        "http://localhost:5173/payment/refresh")));
    }

    private static String signature(long timestamp, String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + java.util.HexFormat.of().formatHex(digest);
    }
}
