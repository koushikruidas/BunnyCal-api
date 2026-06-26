package io.bunnycal.payments.provider;

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
public class StripeProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeProvider.class);

    private final StripeProperties properties;
    private final com.stripe.net.RequestOptions requestOptions;

    public StripeProvider(StripeProperties properties) {
        this.properties = properties;
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
    public ProviderWebhookEvent verifyWebhook(byte[] payload, String signature) {
        String body = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        try {
            Event event = Webhook.constructEvent(body, signature, properties.webhookSecret());
            return new ProviderWebhookEvent(event.getId(), event.getType(), body);
        } catch (SignatureVerificationException e) {
            throw new WebhookVerificationException("Stripe webhook signature verification failed", e);
        }
    }

    private PaymentProviderException wrap(String op, StripeException e) {
        log.warn("stripe.{}.failed code={} status={}", op, e.getCode(), e.getStatusCode(), e);
        return new PaymentProviderException("Stripe " + op + " failed", e);
    }
}
