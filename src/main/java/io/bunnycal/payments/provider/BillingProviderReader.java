package io.bunnycal.payments.provider;

import io.bunnycal.payments.provider.ProviderRequests.OfficialInvoiceRef;
import io.bunnycal.payments.provider.ProviderSnapshots.CheckoutSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the provider gateway. Every operation here is a <em>safe, side-effect-free,
 * retryable</em> query of current provider state — reconciliation depends only on this interface
 * so it can re-read freely without any risk of moving money.
 *
 * <p>Kept separate from {@link BillingProviderWriter} on purpose: reads and writes have opposite
 * risk profiles (a read can be retried at will; a write must be controlled), and reconciliation
 * tests can mock only the reader.
 *
 * <p>Implementations are gated by {@code billing.enabled}; when billing is disabled no bean exists.
 */
public interface BillingProviderReader {

    /** Reads current subscription state, or empty if the provider has no such subscription. */
    Optional<SubscriptionSnapshot> getSubscription(String providerSubscriptionId);

    /** Reads current checkout-session state, or empty if unknown to the provider. */
    Optional<CheckoutSnapshot> getCheckoutSession(String providerSessionId);

    /** Reads a single payment, or empty if unknown to the provider. */
    Optional<PaymentSnapshot> getPayment(String providerPaymentId);

    /**
     * Lists the payments the provider has recorded for a subscription, newest-first not guaranteed.
     * Used by the reconciliation cron to record a receipt when the {@code payment.succeeded} webhook
     * was lost (so the local row activated but no invoice was written). Default returns empty (a
     * provider that cannot list simply records no back-filled receipt).
     */
    default List<PaymentSnapshot> listPaymentsForSubscription(String providerSubscriptionId) {
        return List.of();
    }

    /**
     * Lists all subscriptions the provider has for a customer. Used by the duplicate-purchase
     * guard before starting a checkout. Default returns empty (a provider that cannot list simply
     * skips the guard).
     */
    default List<SubscriptionSnapshot> listSubscriptionsForCustomer(String providerCustomerId) {
        return List.of();
    }

    /**
     * Resolves the legal invoice issued for a payment. Direct-merchant providers may return
     * {@code null}; this is primarily used by Merchant-of-Record implementations.
     */
    default OfficialInvoiceRef findOfficialInvoice(String providerPaymentId) {
        return null;
    }
}
