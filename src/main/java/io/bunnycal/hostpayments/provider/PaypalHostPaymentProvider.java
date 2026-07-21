package io.bunnycal.hostpayments.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.bunnycal.hostpayments.config.HostCommerceProperties;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "${commerce.enabled:false} && ${commerce.paypal.enabled:false}")
public class PaypalHostPaymentProvider implements HostPaymentProvider {
    private final HostCommerceProperties.Paypal config;
    private final ObjectMapper json;
    private final RestClient client;
    private volatile AccessToken cachedToken;

    public PaypalHostPaymentProvider(HostCommerceProperties properties, ObjectMapper json,
                                     RestClient.Builder clientBuilder) {
        this.config = properties.paypal();
        this.json = json;
        if (config == null || !config.enabled()) throw new IllegalStateException("PayPal commerce is not enabled");
        require(config.clientId(), "COMMERCE_PAYPAL_CLIENT_ID");
        require(config.clientSecret(), "COMMERCE_PAYPAL_CLIENT_SECRET");
        require(config.partnerMerchantId(), "COMMERCE_PAYPAL_PARTNER_MERCHANT_ID");
        require(config.partnerAttributionId(), "COMMERCE_PAYPAL_PARTNER_ATTRIBUTION_ID");
        require(config.webhookId(), "COMMERCE_PAYPAL_WEBHOOK_ID");
        this.client = clientBuilder.baseUrl(config.apiBaseUrl()).build();
    }

    @Override public PaymentProviderType type() { return PaymentProviderType.PAYPAL; }
    @Override public String publicClientKey() { return config.clientId(); }

    @Override
    public ConnectedAccount createConnectedAccount(UUID userId, String email, String name) {
        String tracking = "bunnycal_" + userId.toString().replace("-", "")
                + "_" + UUID.randomUUID().toString().replace("-", "");
        return new ConnectedAccount(tracking, false, false, false, "Complete PayPal onboarding");
    }

    @Override
    public String createOnboardingLink(String accountOrTrackingId) {
        ObjectNode root = json.createObjectNode();
        root.put("tracking_id", accountOrTrackingId);
        var operation = root.putArray("operations").addObject();
        operation.put("operation", "API_INTEGRATION");
        operation.putObject("api_integration_preference").putObject("rest_api_integration")
                .put("integration_method", "PAYPAL").put("integration_type", "THIRD_PARTY")
                .putObject("third_party_details").putArray("features").add("PAYMENT").add("REFUND");
        root.putArray("products").add("EXPRESS_CHECKOUT");
        root.putArray("legal_consents").addObject().put("type", "SHARE_DATA_CONSENT").put("granted", true);
        root.putObject("partner_config_override").put("return_url", config.onboardingReturnUrl())
                .put("return_url_description", "Return to BunnyCal");
        JsonNode response = post("/v2/customer/partner-referrals", root, null, null);
        return link(response, "action_url");
    }

    @Override
    public ConnectedAccount retrieveConnectedAccount(String accountOrTrackingId) {
        String merchantId = accountOrTrackingId.startsWith("bunnycal_")
                ? merchantIdForTracking(accountOrTrackingId) : accountOrTrackingId;
        if (merchantId == null) {
            return new ConnectedAccount(accountOrTrackingId, false, false, false, "Complete PayPal onboarding");
        }
        JsonNode status = get("/v1/customer/partners/" + config.partnerMerchantId()
                + "/merchant-integrations/" + merchantId, merchantId);
        boolean receivable = status.path("payments_receivable").asBoolean(false);
        boolean emailConfirmed = status.path("primary_email_confirmed").asBoolean(false);
        boolean consent = status.path("oauth_integrations").isArray() && !status.path("oauth_integrations").isEmpty();
        boolean ready = receivable && emailConfirmed && consent;
        return new ConnectedAccount(merchantId, ready, ready, emailConfirmed && consent,
                ready ? null : "Finish PayPal account verification and grant payment permissions");
    }

    @Override
    public CreatedPayment createPayment(CreatePayment request) {
        ObjectNode root = json.createObjectNode().put("intent", "CAPTURE");
        ObjectNode unit = root.putArray("purchase_units").addObject();
        unit.put("reference_id", request.paymentId().toString());
        unit.put("custom_id", request.paymentId().toString());
        unit.put("description", request.description());
        unit.putObject("payee").put("merchant_id", request.providerAccountId());
        unit.putObject("amount").put("currency_code", request.currency())
                .put("value", decimal(request.amountMinor()));
        ObjectNode paypal = root.putObject("payment_source").putObject("paypal");
        if (request.receiptEmail() != null) paypal.put("email_address", request.receiptEmail());
        paypal.putObject("experience_context").put("user_action", "PAY_NOW")
                .put("shipping_preference", "NO_SHIPPING")
                .put("return_url", request.returnUrl()).put("cancel_url", request.cancelUrl());
        JsonNode response = post("/v2/checkout/orders", root, request.providerAccountId(), request.idempotencyKey());
        return new CreatedPayment(text(response, "id"), null, approval(response), mapOrderStatus(text(response, "status")));
    }

    @Override
    public ProviderPayment retrievePayment(String merchantId, String orderId) {
        return toPayment(get("/v2/checkout/orders/" + orderId, merchantId));
    }

    @Override
    public ProviderPayment completePayment(String merchantId, String orderId) {
        ProviderPayment current = retrievePayment(merchantId, orderId);
        if (current.status() == ProviderPaymentStatus.REQUIRES_ACTION && current.approvalUrl() == null) {
            JsonNode captured = post("/v2/checkout/orders/" + orderId + "/capture",
                    json.createObjectNode(), merchantId, "host-payment-capture-" + orderId);
            return toPayment(captured);
        }
        return current;
    }

    @Override public void cancelPayment(String merchantId, String orderId) { /* uncaptured Orders move no funds */ }

    @Override
    public void refundPayment(String merchantId, String orderId, String idempotencyKey) {
        ProviderPayment payment = retrievePayment(merchantId, orderId);
        if (payment.chargeId() == null) throw new HostPaymentProviderException("refundPayment", new IllegalStateException("PayPal capture not found"));
        post("/v2/payments/captures/" + payment.chargeId() + "/refund",
                json.createObjectNode(), merchantId, idempotencyKey);
    }

    @Override
    public VerifiedWebhook verifyWebhook(byte[] payload, Map<String, String> headers) {
        String raw = new String(payload, StandardCharsets.UTF_8);
        JsonNode event;
        try { event = json.readTree(raw); } catch (Exception e) { throw new HostPaymentProviderException("verifyWebhook", e); }
        ObjectNode verify = json.createObjectNode();
        verify.put("auth_algo", header(headers, "paypal-auth-algo"));
        verify.put("cert_url", header(headers, "paypal-cert-url"));
        verify.put("transmission_id", header(headers, "paypal-transmission-id"));
        verify.put("transmission_sig", header(headers, "paypal-transmission-sig"));
        verify.put("transmission_time", header(headers, "paypal-transmission-time"));
        verify.put("webhook_id", config.webhookId());
        verify.set("webhook_event", event);
        JsonNode result = post("/v1/notifications/verify-webhook-signature", verify, null, null);
        if (!"SUCCESS".equalsIgnoreCase(text(result, "verification_status"))) {
            throw new HostPaymentProviderException("verifyWebhook", new SecurityException("PayPal webhook signature rejected"));
        }
        String type = text(event, "event_type");
        JsonNode resource = event.path("resource");
        String orderId = resource.path("supplementary_data").path("related_ids").path("order_id").asText(null);
        if (orderId == null && type != null && type.startsWith("CHECKOUT.ORDER")) orderId = text(resource, "id");
        String normalized = switch (type == null ? "" : type) {
            case "PAYMENT.CAPTURE.COMPLETED", "CHECKOUT.ORDER.COMPLETED" -> "payment_intent.succeeded";
            case "PAYMENT.CAPTURE.REFUNDED" -> "charge.refunded";
            case "CUSTOMER.DISPUTE.CREATED" -> "charge.dispute.created";
            case "MERCHANT.PARTNER-CONSENT.REVOKED" -> "account.application.deauthorized";
            default -> type;
        };
        Long refund = "PAYMENT.CAPTURE.REFUNDED".equals(type) ? minor(resource.path("amount")) : null;
        return new VerifiedWebhook(text(event, "id"), normalized, payeeMerchant(resource), orderId, refund, null, raw);
    }

    private ProviderPayment toPayment(JsonNode order) {
        JsonNode unit = order.path("purchase_units").path(0);
        JsonNode amount = unit.path("amount");
        JsonNode capture = unit.path("payments").path("captures").path(0);
        String orderStatus = text(order, "status");
        ProviderPaymentStatus status = mapOrderStatus(orderStatus);
        if ("COMPLETED".equals(orderStatus) || "COMPLETED".equals(text(capture, "status"))) status = ProviderPaymentStatus.SUCCEEDED;
        Map<String, String> metadata = new HashMap<>();
        String customId = text(unit, "custom_id");
        if (customId != null) metadata.put("bunnycal_payment_id", customId);
        return new ProviderPayment(text(order, "id"), null, approval(order), text(capture, "id"), status,
                minor(amount), text(amount, "currency_code"), metadata);
    }

    private JsonNode post(String path, JsonNode body, String merchantId, String requestId) {
        try {
            String response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> authHeaders(h, merchantId, requestId)).body(body.toString())
                    .retrieve().body(String.class);
            return parse(response);
        } catch (RuntimeException e) { throw new HostPaymentProviderException(path, e); }
    }

    private JsonNode get(String path, String merchantId) {
        try {
            String response = client.get().uri(path).headers(h -> authHeaders(h, merchantId, null))
                    .retrieve().body(String.class);
            return parse(response);
        }
        catch (RuntimeException e) { throw new HostPaymentProviderException(path, e); }
    }

    private void authHeaders(org.springframework.http.HttpHeaders headers, String merchantId, String requestId) {
        headers.setBearerAuth(accessToken());
        headers.set("PayPal-Partner-Attribution-Id", config.partnerAttributionId());
        if (merchantId != null && !merchantId.startsWith("bunnycal_")) headers.set("PayPal-Auth-Assertion", assertion(merchantId));
        if (requestId != null) headers.set("PayPal-Request-Id", requestId);
    }

    private synchronized String accessToken() {
        if (cachedToken != null && cachedToken.expiresAt().isAfter(Instant.now().plusSeconds(30))) return cachedToken.value();
        try {
            String response = client.post().uri("/v1/oauth2/token")
                    .headers(h -> h.setBasicAuth(config.clientId(), config.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body("grant_type=client_credentials")
                    .retrieve().body(String.class);
            JsonNode token = parse(response);
            cachedToken = new AccessToken(text(token, "access_token"), Instant.now().plusSeconds(token.path("expires_in").asLong(300)));
            return cachedToken.value();
        } catch (RuntimeException e) { throw new HostPaymentProviderException("accessToken", e); }
    }

    private String merchantIdForTracking(String tracking) {
        JsonNode response = get("/v1/customer/partners/" + config.partnerMerchantId()
                + "/merchant-integrations?tracking_id=" + tracking, null);
        JsonNode first = response.path("merchant_integrations").path(0);
        return first.isMissingNode() ? null : text(first, "merchant_id");
    }

    private String assertion(String merchantId) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"iss\":\"" + config.clientId() + "\",\"payer_id\":\"" + merchantId + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (Exception exception) { throw new HostPaymentProviderException("parseResponse", exception); }
    }

    private static ProviderPaymentStatus mapOrderStatus(String status) {
        if ("COMPLETED".equals(status)) return ProviderPaymentStatus.SUCCEEDED;
        if ("VOIDED".equals(status)) return ProviderPaymentStatus.CANCELLED;
        if ("APPROVED".equals(status)) return ProviderPaymentStatus.REQUIRES_ACTION;
        if ("PAYER_ACTION_REQUIRED".equals(status) || "CREATED".equals(status) || "SAVED".equals(status)) return ProviderPaymentStatus.REQUIRES_ACTION;
        return ProviderPaymentStatus.PROCESSING;
    }

    private static String decimal(long minor) { return BigDecimal.valueOf(minor, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
    private static long minor(JsonNode amount) { String value = text(amount, "value"); return value == null ? 0 : new BigDecimal(value).movePointRight(2).longValueExact(); }
    private static String approval(JsonNode node) { String value = link(node, "payer-action"); return value == null ? link(node, "approve") : value; }
    private static String link(JsonNode node, String rel) { for (JsonNode link : node.path("links")) if (rel.equals(text(link, "rel"))) return text(link, "href"); return null; }
    private static String text(JsonNode node, String field) { JsonNode value = node == null ? null : node.get(field); return value == null || value.isNull() ? null : value.asText(); }
    private static String header(Map<String, String> headers, String name) { return headers.entrySet().stream().filter(e -> name.equalsIgnoreCase(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(""); }
    private static String payeeMerchant(JsonNode resource) { String id = resource.path("payee").path("merchant_id").asText(null); return id == null ? resource.path("seller").path("merchant_id").asText(null) : id; }
    private static void require(String value, String name) { if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required when PayPal commerce is enabled"); }
    private record AccessToken(String value, Instant expiresAt) {}
}
