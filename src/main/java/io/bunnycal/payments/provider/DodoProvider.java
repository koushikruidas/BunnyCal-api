package io.bunnycal.payments.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.bunnycal.payments.config.DodoProperties;
import io.bunnycal.payments.provider.ProviderRequests.CancelSubscriptionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSession;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CreateCustomerRequest;
import io.bunnycal.payments.provider.ProviderRequests.CustomerRef;
import io.bunnycal.payments.provider.ProviderRequests.PortalSession;
import io.bunnycal.payments.provider.ProviderRequests.PortalSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Dodo Payments implementation of {@link PaymentProvider} (Merchant-of-Record mode).
 *
 * <p>Gated by {@code billing.enabled=true} AND {@code billing.provider=dodo}. Talks to Dodo's
 * REST API with a {@link RestClient}; the neutral {@link ProviderRequests} value objects are
 * the only types the billing domain sees, exactly as with {@code StripeProvider}.
 *
 * <p>Webhook verification follows the Standard Webhooks specification (the scheme Dodo uses):
 * the signed content is {@code "{webhook-id}.{webhook-timestamp}.{payload}"}, HMAC-SHA256 with
 * the endpoint secret, base64-encoded, compared constant-time against any {@code v1,<sig>}
 * entry in the {@code webhook-signature} header.
 */
@Component
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
@ConditionalOnProperty(name = "billing.provider", havingValue = "dodo")
public class DodoProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(DodoProvider.class);

    private final DodoProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public DodoProvider(DodoProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, buildRestClient(properties));
    }

    DodoProvider(DodoProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(DodoProperties properties) {
        String baseUrl = properties.testMode()
                ? "https://test.dodopayments.com"
                : "https://live.dodopayments.com";
        // Build a dedicated, isolated client (RestClient.builder() — NOT the shared singleton
        // RestClient.Builder bean, which other clients mutate via .baseUrl()/.defaultHeader()).
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + nullToEmpty(properties.apiKey()))
                .build();
    }

    // ---------------------------------------------------------------------------------------
    // Outbound API
    // ---------------------------------------------------------------------------------------

    @Override
    public CustomerRef createCustomer(CreateCustomerRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        // Dodo requires both `email` and `name` as non-null strings; a JSON null is rejected
        // as a missing field. Fall back to an email-derived display name when the user has none.
        body.put("email", request.email());
        body.put("name", nameOrFallback(request.name(), request.email()));
        JsonNode response = post("/customers", body, "createCustomer");
        return new CustomerRef(text(response, "customer_id"));
    }

    /** Dodo rejects null/blank names; derive one from the email local-part as a last resort. */
    private static String nameOrFallback(String name, String email) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return "Customer";
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutSessionRequest request) {
        ObjectNode body = objectMapper.createObjectNode();

        // product_cart: [{ product_id, quantity }] — Dodo keys checkout on product id, which we
        // store generically in SubscriptionPlan.providerPriceId.
        ArrayNode cart = body.putArray("product_cart");
        ObjectNode item = cart.addObject();
        item.put("product_id", request.providerPriceId());
        item.put("quantity", 1);

        // Attach the existing Dodo customer by id.
        ObjectNode customer = body.putObject("customer");
        customer.put("customer_id", request.providerCustomerId());

        body.put("return_url", request.successUrl());

        // metadata.user_id mirrors the Stripe integration so the webhook can resolve the user.
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("user_id", String.valueOf(request.userId()));

        // BunnyCal is the trial authority. Always override the Dodo product default so a
        // dashboard configuration change can never grant or extend a provider-side trial.
        body.putObject("subscription_data").put("trial_period_days", request.trialDays());

        if (request.providerCouponId() != null && !request.providerCouponId().isBlank()) {
            // Dodo applies discounts via a discount code at checkout.
            body.put("discount_code", request.providerCouponId());
        }

        JsonNode response = post("/checkouts", body, "createCheckoutSession");
        return new CheckoutSession(text(response, "session_id"), text(response, "checkout_url"));
    }

    @Override
    public PortalSession createPortalSession(PortalSessionRequest request) {
        // Empty body; Dodo returns a one-time hosted portal link for the customer.
        JsonNode response = post(
                "/customers/" + request.providerCustomerId() + "/customer-portal/session",
                objectMapper.createObjectNode(),
                "createPortalSession");
        // The link field has been seen as both "link" and "url"; accept either.
        String link = text(response, "link");
        if (link == null) {
            link = text(response, "url");
        }
        return new PortalSession(link);
    }

    @Override
    public void cancelSubscription(CancelSubscriptionRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        if (request.atPeriodEnd()) {
            body.put("cancel_at_next_billing_date", true);
        } else {
            body.put("status", "cancelled");
        }
        patch("/subscriptions/" + request.providerSubscriptionId(), body, "cancelSubscription");
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("payment_id", request.providerPaymentIntentId());
        if (request.amountMinor() != null) {
            body.put("amount", request.amountMinor());
        }
        JsonNode response = post("/refunds", body, "refund");
        return new RefundResult(text(response, "refund_id"), text(response, "status"));
    }

    // ---------------------------------------------------------------------------------------
    // Webhook verification + normalization (Standard Webhooks spec)
    // ---------------------------------------------------------------------------------------

    @Override
    public ProviderWebhookEvent verifyWebhook(byte[] payload, Map<String, String> headers) {
        String body = new String(payload, StandardCharsets.UTF_8);
        String id = header(headers, "webhook-id");
        String timestamp = header(headers, "webhook-timestamp");
        String signature = header(headers, "webhook-signature");
        verifySignature(id, timestamp, body, signature);

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            // Signature was valid but body is unparseable — treat as a permanent UNKNOWN so the
            // event is recorded and not retried forever.
            log.warn("dodo.webhook.unparseable id={}", id, e);
            return new ProviderWebhookEvent(id, "unparseable", BillingEventType.UNKNOWN, body,
                    ProviderWebhookEvent.Data.empty());
        }

        String rawType = text(root, "type");
        BillingEventType type = mapType(rawType);
        JsonNode data = root.path("data");
        ProviderWebhookEvent.Data normalized = type == BillingEventType.UNKNOWN
                ? ProviderWebhookEvent.Data.empty()
                : extractData(type, data);
        // During initial Dodo rollout, dump the raw body so field mappings can be verified
        // against real payloads. Lower to DEBUG (or remove) once the mappings are confirmed.
        log.info("dodo.webhook.received id={} rawType={} mappedTo={} body={}", id, rawType, type, body);
        return new ProviderWebhookEvent(id, rawType, type, body, normalized);
    }

    private void verifySignature(String id, String timestamp, String body, String signatureHeader) {
        if (id == null || timestamp == null || signatureHeader == null) {
            throw new WebhookVerificationException("Dodo webhook missing required headers");
        }
        String secret = properties.webhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new WebhookVerificationException("Dodo webhook secret is not configured");
        }
        // Standard Webhooks: secret may be prefixed with "whsec_"; the remainder is base64.
        byte[] key = secret.startsWith("whsec_")
                ? Base64.getDecoder().decode(secret.substring("whsec_".length()))
                : secret.getBytes(StandardCharsets.UTF_8);

        String signedContent = id + "." + timestamp + "." + body;
        String expected = base64HmacSha256(key, signedContent);

        // Header is space-delimited "v1,<sig> v1a,<sig> ..."; match any v1 entry constant-time.
        for (String part : signatureHeader.split(" ")) {
            int comma = part.indexOf(',');
            String sig = comma >= 0 ? part.substring(comma + 1) : part;
            if (constantTimeEquals(sig, expected)) {
                return;
            }
        }
        throw new WebhookVerificationException("Dodo webhook signature verification failed");
    }

    private static String base64HmacSha256(byte[] key, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new WebhookVerificationException("Failed to compute Dodo webhook signature", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static BillingEventType mapType(String dodoType) {
        if (dodoType == null) {
            return BillingEventType.UNKNOWN;
        }
        return switch (dodoType) {
            case "subscription.active" -> BillingEventType.CHECKOUT_COMPLETED;
            case "subscription.updated", "subscription.renewed", "subscription.on_hold" ->
                    BillingEventType.SUBSCRIPTION_UPSERTED;
            case "subscription.cancelled", "subscription.expired", "subscription.failed" ->
                    BillingEventType.SUBSCRIPTION_DELETED;
            case "payment.succeeded" -> BillingEventType.INVOICE_PAID;
            case "payment.failed" -> BillingEventType.INVOICE_FAILED;
            case "refund.succeeded" -> BillingEventType.REFUND_PROCESSED;
            default -> BillingEventType.UNKNOWN;
        };
    }

    /**
     * Extracts neutral fields from Dodo's {@code data} object. Dodo carries subscription and
     * payment details inline; field names differ from Stripe (handled entirely here).
     */
    private ProviderWebhookEvent.Data extractData(BillingEventType type, JsonNode data) {
        return switch (type) {
            case CHECKOUT_COMPLETED -> ProviderWebhookEvent.Data.builder()
                    .providerSubscriptionId(text(data, "subscription_id"))
                    .providerCustomerId(customerId(data))
                    .userId(text(data.path("metadata"), "user_id"))
                    .status(ProviderWebhookEvent.SubscriptionStatusSignal.ACTIVE)
                    .build();
            case SUBSCRIPTION_UPSERTED -> ProviderWebhookEvent.Data.builder()
                    .providerSubscriptionId(text(data, "subscription_id"))
                    .providerCustomerId(customerId(data))
                    .status(mapSubscriptionStatus(text(data, "status")))
                    .cancelAtPeriodEnd(data.path("cancel_at_next_billing_date").asBoolean(false))
                    .currentPeriodStart(isoToInstant(text(data, "previous_billing_date")))
                    .currentPeriodEnd(isoToInstant(text(data, "next_billing_date")))
                    .build();
            case SUBSCRIPTION_DELETED -> ProviderWebhookEvent.Data.builder()
                    .providerSubscriptionId(text(data, "subscription_id"))
                    .build();
            case INVOICE_PAID -> paymentData(data)
                    .status(ProviderWebhookEvent.SubscriptionStatusSignal.ACTIVE)
                    .build();
            case INVOICE_FAILED -> paymentData(data).build();
            case REFUND_PROCESSED -> ProviderWebhookEvent.Data.builder()
                    .refundProviderInvoiceId(text(data, "payment_id"))
                    .providerPaymentIntentId(text(data, "payment_id"))
                    .providerRefundId(text(data, "refund_id"))
                    .amountRefundedMinor(data.path("amount").asLong(0))
                    .build();
            case UNKNOWN -> ProviderWebhookEvent.Data.empty();
        };
    }

    private ProviderWebhookEvent.Data.Builder paymentData(JsonNode data) {
        long total = data.path("total_amount").asLong(data.path("amount").asLong(0));
        long tax = data.path("tax").asLong(0);
        long subtotal = data.has("recurring_pre_tax_amount")
                ? data.path("recurring_pre_tax_amount").asLong(0)
                : Math.max(0, total - tax);
        return ProviderWebhookEvent.Data.builder()
                .providerSubscriptionId(text(data, "subscription_id"))
                .providerCustomerId(customerId(data))
                .providerInvoiceId(text(data, "payment_id"))
                .providerPaymentIntentId(text(data, "payment_id"))
                .subtotalMinor(subtotal)
                .discountMinor(Math.max(0, subtotal - total + tax))
                .totalMinor(total)
                .currency(text(data, "currency"))
                .invoicePeriodStart(isoToInstant(text(data, "previous_billing_date")))
                .invoicePeriodEnd(isoToInstant(text(data, "next_billing_date")));
    }

    /** Customer id may be a bare string or a nested object {customer_id: ...}. */
    private static String customerId(JsonNode data) {
        JsonNode c = data.path("customer");
        if (c.isObject()) {
            return text(c, "customer_id");
        }
        String direct = text(data, "customer_id");
        return direct != null ? direct : (c.isTextual() ? c.asText() : null);
    }

    private static ProviderWebhookEvent.SubscriptionStatusSignal mapSubscriptionStatus(String status) {
        if (status == null) {
            return ProviderWebhookEvent.SubscriptionStatusSignal.INCOMPLETE;
        }
        return switch (status) {
            case "active" -> ProviderWebhookEvent.SubscriptionStatusSignal.ACTIVE;
            case "on_hold", "failed" -> ProviderWebhookEvent.SubscriptionStatusSignal.PAST_DUE;
            case "cancelled", "expired" -> ProviderWebhookEvent.SubscriptionStatusSignal.CANCELLED;
            case "pending" -> ProviderWebhookEvent.SubscriptionStatusSignal.INCOMPLETE;
            default -> ProviderWebhookEvent.SubscriptionStatusSignal.INCOMPLETE;
        };
    }

    // ---------------------------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------------------------

    private JsonNode post(String path, JsonNode body, String op) {
        String json = writeJson(body);
        log.info("dodo.{}.request path={} body={}", op, path, json);
        try {
            String response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);
            return readJson(response);
        } catch (RestClientException e) {
            throw wrap(op, e);
        }
    }

    private JsonNode patch(String path, JsonNode body, String op) {
        String json = writeJson(body);
        try {
            String response = restClient.patch()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);
            return readJson(response);
        } catch (RestClientException e) {
            throw wrap(op, e);
        }
    }

    /** Serialize the body ourselves so the wire bytes exactly match what we log. */
    private String writeJson(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new PaymentProviderException("Failed to serialize Dodo request body", e);
        }
    }

    /**
     * Parse a response body with our own ObjectMapper. The isolated RestClient does not carry
     * the app's Jackson message converters, so we read the raw String and parse it here.
     */
    private JsonNode readJson(String response) {
        try {
            return response == null || response.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(response);
        } catch (Exception e) {
            throw new PaymentProviderException("Failed to parse Dodo response body", e);
        }
    }

    private PaymentProviderException wrap(String op, RestClientException e) {
        // Surface Dodo's actual error body (e.g. which field was rejected) — invaluable while
        // wiring the integration. RestClientResponseException carries the response payload.
        if (e instanceof org.springframework.web.client.RestClientResponseException re) {
            log.warn("dodo.{}.failed status={} body={}", op, re.getStatusCode().value(),
                    re.getResponseBodyAsString());
        } else {
            log.warn("dodo.{}.failed", op, e);
        }
        return new PaymentProviderException("Dodo " + op + " failed", e);
    }

    private static String header(Map<String, String> headers, String name) {
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Instant isoToInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
