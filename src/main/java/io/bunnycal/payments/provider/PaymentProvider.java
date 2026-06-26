package io.bunnycal.payments.provider;

import io.bunnycal.payments.provider.ProviderRequests.CancelSubscriptionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSession;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CreateCustomerRequest;
import io.bunnycal.payments.provider.ProviderRequests.CustomerRef;
import io.bunnycal.payments.provider.ProviderRequests.PortalSession;
import io.bunnycal.payments.provider.ProviderRequests.PortalSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundResult;

/**
 * Provider-agnostic gateway for SaaS subscription billing.
 *
 * <p>This abstraction deliberately exposes only the operations the billing domain
 * needs, using neutral value objects ({@link ProviderRequests}) so that Stripe SDK
 * types never leak into {@code io.bunnycal.billing}. A second provider (e.g. Razorpay)
 * can be added later by implementing this interface without touching any billing
 * business logic.
 *
 * <p>Implementations are gated by {@code billing.enabled}; when billing is disabled
 * no implementation bean is registered.
 */
public interface PaymentProvider {

    /** Creates (or returns) the provider-side customer for a user. */
    CustomerRef createCustomer(CreateCustomerRequest request);

    /**
     * Creates a hosted Checkout session for a subscription purchase.
     *
     * @return the redirect URL the frontend should send the user to.
     */
    CheckoutSession createCheckoutSession(CheckoutSessionRequest request);

    /**
     * Creates a hosted Customer Portal session for card management / cancellation.
     *
     * @return the redirect URL the frontend should send the user to.
     */
    PortalSession createPortalSession(PortalSessionRequest request);

    /** Cancels a subscription, either at period end or immediately. */
    void cancelSubscription(CancelSubscriptionRequest request);

    /** Issues a full or partial refund against a charge/payment. */
    RefundResult refund(RefundRequest request);

    /**
     * Verifies the authenticity of an inbound webhook callback and parses it into a
     * neutral {@link ProviderWebhookEvent}.
     *
     * @param payload   the raw request body bytes (must be the exact bytes received)
     * @param signature the provider signature header value
     * @throws WebhookVerificationException if the signature is invalid
     */
    ProviderWebhookEvent verifyWebhook(byte[] payload, String signature);
}
