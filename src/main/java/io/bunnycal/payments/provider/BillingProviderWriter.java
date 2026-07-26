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
 * Write side of the provider gateway. Every operation here <em>creates money movement or mutates
 * provider-side state</em> and must be controlled — never issued speculatively or retried blindly.
 *
 * <p>Kept separate from {@link BillingProviderReader} on purpose: reconciliation must not be able
 * to reach a writer, and command paths can mock only this interface.
 */
public interface BillingProviderWriter {

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
}
