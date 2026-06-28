package io.bunnycal.payments.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import io.bunnycal.payments.config.StripeProperties;
import io.bunnycal.payments.provider.ProviderRequests.CancelSubscriptionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSession;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CreateCustomerRequest;
import io.bunnycal.payments.provider.ProviderRequests.CustomerRef;
import io.bunnycal.payments.provider.ProviderRequests.PortalSession;
import io.bunnycal.payments.provider.ProviderRequests.PortalSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundResult;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stripe implementation of {@link PaymentProvider}.
 *
 * <p>Gated by {@code billing.enabled=true}. The Stripe API key is set per-request via
 * {@link com.stripe.net.RequestOptions} so the global static key is never mutated (safe
 * if multiple providers/keys coexist later). All Stripe SDK exceptions are wrapped in
 * {@link PaymentProviderException} so callers depend only on this package.
 */
@Component
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe", matchIfMissing = true)
public class StripeProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeProvider.class);

    private final StripeProperties properties;
    private final ObjectMapper objectMapper;
    private final com.stripe.net.RequestOptions requestOptions;

    public StripeProvider(StripeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.requestOptions = com.stripe.net.RequestOptions.builder()
                .setApiKey(properties.secretKey())
                .build();
    }

    @Override
    public CustomerRef createCustomer(CreateCustomerRequest request) {
        try {
            Customer customer = Customer.create(
                    CustomerCreateParams.builder()
                            .setEmail(request.email())
                            .setName(request.name())
                            .putMetadata("user_id", String.valueOf(request.userId()))
                            .build(),
                    requestOptions);
            return new CustomerRef(customer.getId());
        } catch (StripeException e) {
            throw wrap("createCustomer", e);
        }
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutSessionRequest request) {
        try {
            SessionCreateParams.Builder builder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(request.providerCustomerId())
                    .setSuccessUrl(request.successUrl())
                    .setCancelUrl(request.cancelUrl())
                    .putMetadata("user_id", String.valueOf(request.userId()))
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(request.providerPriceId())
                            .setQuantity(1L)
                            .build());

            if (request.trialDays() != null && request.trialDays() > 0) {
                builder.setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .setTrialPeriodDays(request.trialDays().longValue())
                        .build());
            }

            if (request.providerCouponId() != null && !request.providerCouponId().isBlank()) {
                builder.addDiscount(SessionCreateParams.Discount.builder()
                        .setCoupon(request.providerCouponId())
                        .build());
            }

            com.stripe.model.checkout.Session session =
                    com.stripe.model.checkout.Session.create(builder.build(), requestOptions);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw wrap("createCheckoutSession", e);
        }
    }

    @Override
    public PortalSession createPortalSession(PortalSessionRequest request) {
        try {
            com.stripe.model.billingportal.Session session =
                    com.stripe.model.billingportal.Session.create(
                            com.stripe.param.billingportal.SessionCreateParams.builder()
                                    .setCustomer(request.providerCustomerId())
                                    .setReturnUrl(request.returnUrl())
                                    .build(),
                            requestOptions);
            return new PortalSession(session.getUrl());
        } catch (StripeException e) {
            throw wrap("createPortalSession", e);
        }
    }

    @Override
    public void cancelSubscription(CancelSubscriptionRequest request) {
        try {
            Subscription subscription =
                    Subscription.retrieve(request.providerSubscriptionId(), requestOptions);
            if (request.atPeriodEnd()) {
                subscription.update(
                        SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build(),
                        requestOptions);
            } else {
                subscription.cancel(SubscriptionCancelParams.builder().build(), requestOptions);
            }
        } catch (StripeException e) {
            throw wrap("cancelSubscription", e);
        }
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        try {
            RefundCreateParams.Builder builder = RefundCreateParams.builder()
                    .setPaymentIntent(request.providerPaymentIntentId());
            if (request.amountMinor() != null) {
                builder.setAmount(request.amountMinor());
            }
            Refund refund = Refund.create(builder.build(), requestOptions);
            return new RefundResult(refund.getId(), refund.getStatus());
        } catch (StripeException e) {
            throw wrap("refund", e);
        }
    }

    @Override
    public ProviderWebhookEvent verifyWebhook(byte[] payload, java.util.Map<String, String> headers) {
        String body = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        String signature = header(headers, "Stripe-Signature");
        try {
            Event event = Webhook.constructEvent(body, signature, properties.webhookSecret());
            BillingEventType type = mapType(event.getType());
            ProviderWebhookEvent.Data data = type == BillingEventType.UNKNOWN
                    ? ProviderWebhookEvent.Data.empty()
                    : extractData(type, dataObject(body));
            return new ProviderWebhookEvent(event.getId(), event.getType(), type, body, data);
        } catch (SignatureVerificationException e) {
            throw new WebhookVerificationException("Stripe webhook signature verification failed", e);
        }
    }

    private static String header(java.util.Map<String, String> headers, String name) {
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static BillingEventType mapType(String stripeType) {
        return switch (stripeType) {
            case "checkout.session.completed" -> BillingEventType.CHECKOUT_COMPLETED;
            case "customer.subscription.created", "customer.subscription.updated" ->
                    BillingEventType.SUBSCRIPTION_UPSERTED;
            case "customer.subscription.deleted" -> BillingEventType.SUBSCRIPTION_DELETED;
            case "invoice.paid" -> BillingEventType.INVOICE_PAID;
            case "invoice.payment_failed" -> BillingEventType.INVOICE_FAILED;
            case "charge.refunded" -> BillingEventType.REFUND_PROCESSED;
            default -> BillingEventType.UNKNOWN;
        };
    }

    /** Extracts the neutral {@link ProviderWebhookEvent.Data} from {@code data.object} per event type. */
    private ProviderWebhookEvent.Data extractData(BillingEventType type, JsonNode object) {
        return switch (type) {
            case CHECKOUT_COMPLETED -> base()
                    .providerCustomerId(text(object, "customer"))
                    .providerSubscriptionId(text(object, "subscription"))
                    .userId(text(object.path("metadata"), "user_id"))
                    .build();
            case SUBSCRIPTION_UPSERTED -> base()
                    .providerSubscriptionId(text(object, "id"))
                    .providerCustomerId(text(object, "customer"))
                    .status(mapStatus(text(object, "status")))
                    .cancelAtPeriodEnd(object.path("cancel_at_period_end").asBoolean(false))
                    .currentPeriodStart(epochToInstant(object, "current_period_start"))
                    .currentPeriodEnd(epochToInstant(object, "current_period_end"))
                    .build();
            case SUBSCRIPTION_DELETED -> base()
                    .providerSubscriptionId(text(object, "id"))
                    .build();
            case INVOICE_PAID -> invoiceData(object)
                    .card(extractCard(object))
                    .providerPaymentMethodId(text(object, "payment_method"))
                    .build();
            case INVOICE_FAILED -> invoiceData(object).build();
            case REFUND_PROCESSED -> refundData(object);
            case UNKNOWN -> ProviderWebhookEvent.Data.empty();
        };
    }

    private static ProviderWebhookEvent.Data.Builder base() {
        return ProviderWebhookEvent.Data.builder();
    }

    private ProviderWebhookEvent.Data.Builder invoiceData(JsonNode invoice) {
        long subtotal = invoice.path("subtotal").asLong(0);
        long total = invoice.has("amount_paid")
                ? invoice.path("amount_paid").asLong(0)
                : invoice.path("total").asLong(0);
        long discount = Math.max(0, invoice.path("total_discount_amounts").isArray()
                ? sumDiscountAmounts(invoice.path("total_discount_amounts"))
                : Math.max(0, subtotal - total));
        JsonNode firstLinePeriod = invoice.path("lines").path("data").path(0).path("period");
        return base()
                .providerSubscriptionId(text(invoice, "subscription"))
                .providerCustomerId(text(invoice, "customer"))
                .providerInvoiceId(text(invoice, "id"))
                .providerPaymentIntentId(text(invoice, "payment_intent"))
                .subtotalMinor(subtotal)
                .discountMinor(discount)
                .totalMinor(total)
                .currency(text(invoice, "currency"))
                .invoicePeriodStart(epochToInstant(firstLinePeriod, "start"))
                .invoicePeriodEnd(epochToInstant(firstLinePeriod, "end"));
    }

    private ProviderWebhookEvent.Data refundData(JsonNode charge) {
        long amountRefunded = charge.path("amount_refunded").asLong(0);
        JsonNode refunds = charge.path("refunds").path("data");
        String providerRefundId = refunds.isArray() && !refunds.isEmpty()
                ? text(refunds.path(0), "id") : null;
        return base()
                .refundProviderInvoiceId(text(charge, "invoice"))
                .providerPaymentIntentId(text(charge, "payment_intent"))
                .providerRefundId(providerRefundId)
                .amountRefundedMinor(amountRefunded)
                .build();
    }

    private static ProviderWebhookEvent.CardInfo extractCard(JsonNode invoice) {
        JsonNode card = invoice.path("charge").path("payment_method_details").path("card");
        if (card.isMissingNode()) {
            return null;
        }
        return new ProviderWebhookEvent.CardInfo(
                text(card, "brand"),
                text(card, "last4"),
                card.path("exp_month").isNumber() ? card.path("exp_month").asInt() : null,
                card.path("exp_year").isNumber() ? card.path("exp_year").asInt() : null);
    }

    private static long sumDiscountAmounts(JsonNode arr) {
        long sum = 0;
        for (JsonNode n : arr) {
            sum += n.path("amount").asLong(0);
        }
        return sum;
    }

    private static ProviderWebhookEvent.SubscriptionStatusSignal mapStatus(String stripeStatus) {
        if (stripeStatus == null) {
            return ProviderWebhookEvent.SubscriptionStatusSignal.INCOMPLETE;
        }
        return switch (stripeStatus) {
            case "trialing" -> ProviderWebhookEvent.SubscriptionStatusSignal.TRIAL;
            case "active" -> ProviderWebhookEvent.SubscriptionStatusSignal.ACTIVE;
            case "past_due", "unpaid" -> ProviderWebhookEvent.SubscriptionStatusSignal.PAST_DUE;
            case "canceled" -> ProviderWebhookEvent.SubscriptionStatusSignal.CANCELLED;
            default -> ProviderWebhookEvent.SubscriptionStatusSignal.INCOMPLETE;
        };
    }

    private JsonNode dataObject(String body) {
        try {
            return objectMapper.readTree(body).path("data").path("object");
        } catch (Exception e) {
            log.warn("stripe.webhook.unparseable", e);
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Instant epochToInstant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : Instant.ofEpochSecond(v.asLong());
    }

    private PaymentProviderException wrap(String op, StripeException e) {
        log.warn("stripe.{}.failed code={} status={}", op, e.getCode(), e.getStatusCode(), e);
        return new PaymentProviderException("Stripe " + op + " failed", e);
    }
}
