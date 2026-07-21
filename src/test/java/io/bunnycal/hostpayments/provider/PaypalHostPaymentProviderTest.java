package io.bunnycal.hostpayments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.hostpayments.config.HostCommerceProperties;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PaypalHostPaymentProviderTest {
    private static final String BASE = "https://paypal.test";
    private static final String MERCHANT = "SELLER123";

    @Test
    void createsPartnerReferralAndResolvesTrackingIdToReadyMerchant() {
        Fixture fixture = fixture();
        HostPaymentProvider.ConnectedAccount pending = fixture.provider.createConnectedAccount(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "host@example.com", "Host");

        fixture.expectToken();
        fixture.server.expect(requestTo(BASE + "/v2/customer/partner-referrals"))
                .andExpect(method(POST))
                .andExpect(header("PayPal-Partner-Attribution-Id", "BUNNYCAL_SP_PPCP"))
                .andExpect(jsonPath("$.tracking_id").value(pending.accountId()))
                .andExpect(jsonPath("$.operations[0].api_integration_preference.rest_api_integration.third_party_details.features[0]").value("PAYMENT"))
                .andExpect(jsonPath("$.operations[0].api_integration_preference.rest_api_integration.third_party_details.features[1]").value("REFUND"))
                .andExpect(jsonPath("$.products[0]").value("EXPRESS_CHECKOUT"))
                .andRespond(withSuccess("""
                        {"links":[{"rel":"action_url","href":"https://paypal.test/signup"}]}
                        """, MediaType.APPLICATION_JSON));

        fixture.server.expect(requestTo(BASE + "/v1/customer/partners/PARTNER123/merchant-integrations?tracking_id=" + pending.accountId()))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"merchant_integrations":[{"merchant_id":"SELLER123"}]}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(BASE + "/v1/customer/partners/PARTNER123/merchant-integrations/SELLER123"))
                .andExpect(method(GET))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("PayPal-Auth-Assertion"))
                        .contains("."))
                .andRespond(withSuccess("""
                        {"payments_receivable":true,"primary_email_confirmed":true,
                         "oauth_integrations":[{"integration_type":"THIRD_PARTY"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.provider.createOnboardingLink(pending.accountId()))
                .isEqualTo("https://paypal.test/signup");
        HostPaymentProvider.ConnectedAccount ready = fixture.provider.retrieveConnectedAccount(pending.accountId());
        assertThat(ready.accountId()).isEqualTo(MERCHANT);
        assertThat(ready.ready()).isTrue();
        fixture.server.verify();
    }

    @Test
    void createsDirectSellerOrderThenCapturesAndRefundsItWithStableRequestIds() {
        Fixture fixture = fixture();
        UUID paymentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        fixture.expectToken();
        fixture.server.expect(requestTo(BASE + "/v2/checkout/orders"))
                .andExpect(method(POST))
                .andExpect(header("PayPal-Partner-Attribution-Id", "BUNNYCAL_SP_PPCP"))
                .andExpect(header("PayPal-Request-Id", "payment-create-1"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("PayPal-Auth-Assertion")).isNotBlank())
                .andExpect(jsonPath("$.intent").value("CAPTURE"))
                .andExpect(jsonPath("$.purchase_units[0].payee.merchant_id").value(MERCHANT))
                .andExpect(jsonPath("$.purchase_units[0].custom_id").value(paymentId.toString()))
                .andExpect(jsonPath("$.purchase_units[0].amount.currency_code").value("USD"))
                .andExpect(jsonPath("$.purchase_units[0].amount.value").value("12.34"))
                .andExpect(jsonPath("$.payment_source.paypal.experience_context.return_url").value("https://bunnycal.test/book/host/event"))
                .andRespond(withSuccess("""
                        {"id":"ORDER123","status":"CREATED",
                         "links":[{"rel":"payer-action","href":"https://paypal.test/approve/ORDER123"}]}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(BASE + "/v2/checkout/orders/ORDER123"))
                .andExpect(method(GET))
                .andRespond(withSuccess(order("APPROVED", false), MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(BASE + "/v2/checkout/orders/ORDER123/capture"))
                .andExpect(method(POST))
                .andExpect(header("PayPal-Request-Id", "host-payment-capture-ORDER123"))
                .andRespond(withSuccess(order("COMPLETED", true), MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(BASE + "/v2/checkout/orders/ORDER123"))
                .andExpect(method(GET))
                .andRespond(withSuccess(order("COMPLETED", true), MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(BASE + "/v2/payments/captures/CAPTURE123/refund"))
                .andExpect(method(POST))
                .andExpect(header("PayPal-Request-Id", "refund-1"))
                .andRespond(withSuccess("{\"id\":\"REFUND123\",\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));

        HostPaymentProvider.CreatedPayment created = fixture.provider.createPayment(new HostPaymentProvider.CreatePayment(
                paymentId, UUID.randomUUID(), "BOOKING", MERCHANT, 1234, "USD", "Consultation",
                "guest@example.com", "payment-create-1", "https://bunnycal.test/book/host/event",
                "https://bunnycal.test/book/host/event"));
        assertThat(created.providerPaymentId()).isEqualTo("ORDER123");
        assertThat(created.approvalUrl()).isEqualTo("https://paypal.test/approve/ORDER123");
        assertThat(created.status()).isEqualTo(HostPaymentProvider.ProviderPaymentStatus.REQUIRES_ACTION);

        HostPaymentProvider.ProviderPayment completed = fixture.provider.completePayment(MERCHANT, "ORDER123");
        assertThat(completed.status()).isEqualTo(HostPaymentProvider.ProviderPaymentStatus.SUCCEEDED);
        assertThat(completed.chargeId()).isEqualTo("CAPTURE123");
        assertThat(completed.amountMinor()).isEqualTo(1234);
        assertThat(completed.metadata()).containsEntry("bunnycal_payment_id", paymentId.toString());

        fixture.provider.refundPayment(MERCHANT, "ORDER123", "refund-1");
        fixture.server.verify();
    }

    @Test
    void verifiesWebhookRemotelyBeforeNormalizingCaptureEvent() {
        Fixture fixture = fixture();
        String payload = """
                {"id":"WH-1","event_type":"PAYMENT.CAPTURE.COMPLETED","resource":{
                  "id":"CAPTURE123","payee":{"merchant_id":"SELLER123"},
                  "supplementary_data":{"related_ids":{"order_id":"ORDER123"}}
                }}
                """;
        fixture.expectToken();
        fixture.server.expect(requestTo(BASE + "/v1/notifications/verify-webhook-signature"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.transmission_id").value("transmission-1"))
                .andExpect(jsonPath("$.transmission_sig").value("signature-1"))
                .andExpect(jsonPath("$.webhook_id").value("WEBHOOK123"))
                .andExpect(jsonPath("$.webhook_event.id").value("WH-1"))
                .andRespond(withSuccess("{\"verification_status\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        HostPaymentProvider.VerifiedWebhook event = fixture.provider.verifyWebhook(
                payload.getBytes(StandardCharsets.UTF_8), Map.of(
                        "Paypal-Auth-Algo", "SHA256withRSA",
                        "Paypal-Cert-Url", "https://paypal.test/cert.pem",
                        "Paypal-Transmission-Id", "transmission-1",
                        "Paypal-Transmission-Sig", "signature-1",
                        "Paypal-Transmission-Time", "2026-07-21T10:00:00Z"));

        assertThat(event.eventId()).isEqualTo("WH-1");
        assertThat(event.eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(event.providerAccountId()).isEqualTo(MERCHANT);
        assertThat(event.providerPaymentId()).isEqualTo("ORDER123");
        fixture.server.verify();
    }

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HostCommerceProperties.Paypal paypal = new HostCommerceProperties.Paypal(
                true, "CLIENT123", "SECRET123", "PARTNER123", "BUNNYCAL_SP_PPCP",
                "WEBHOOK123", "https://bunnycal.test/integrations?payment=paypal", BASE);
        PaypalHostPaymentProvider provider = new PaypalHostPaymentProvider(
                new HostCommerceProperties(true, "https://bunnycal.test", null, paypal),
                new ObjectMapper(), builder);
        return new Fixture(provider, server);
    }

    private static String order(String status, boolean captured) {
        return """
                {"id":"ORDER123","status":"%s","purchase_units":[{
                  "custom_id":"22222222-2222-2222-2222-222222222222",
                  "amount":{"currency_code":"USD","value":"12.34"}%s
                }]}
                """.formatted(status, captured
                ? ",\"payments\":{\"captures\":[{\"id\":\"CAPTURE123\",\"status\":\"COMPLETED\"}]}"
                : "");
    }

    private record Fixture(PaypalHostPaymentProvider provider, MockRestServiceServer server) {
        void expectToken() {
            server.expect(requestTo(BASE + "/v1/oauth2/token"))
                    .andExpect(method(POST))
                    .andExpect(header("Authorization", "Basic Q0xJRU5UMTIzOlNFQ1JFVDEyMw=="))
                    .andRespond(withSuccess("{\"access_token\":\"ACCESS123\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        }
    }
}
