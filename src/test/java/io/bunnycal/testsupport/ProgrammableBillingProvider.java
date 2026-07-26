package io.bunnycal.testsupport;

import io.bunnycal.payments.provider.PaymentProvider;
import io.bunnycal.payments.provider.ProviderRequests.CancelSubscriptionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSession;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.CreateCustomerRequest;
import io.bunnycal.payments.provider.ProviderRequests.CustomerRef;
import io.bunnycal.payments.provider.ProviderRequests.PortalSession;
import io.bunnycal.payments.provider.ProviderRequests.PortalSessionRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundRequest;
import io.bunnycal.payments.provider.ProviderRequests.RefundResult;
import io.bunnycal.payments.provider.ProviderSnapshots.CheckoutSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.PaymentSnapshot;
import io.bunnycal.payments.provider.ProviderSnapshots.SubscriptionSnapshot;
import io.bunnycal.payments.provider.ProviderWebhookEvent;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link PaymentProvider} test double for the reconcile-by-read flow. Tests register the
 * provider state they want the reader to return (via {@code putSubscription}/{@code putPayment}/
 * {@code putCheckout}) before firing a webhook or a reconcile, so the handler's provider read
 * yields the intended snapshot. Write operations return canned values.
 */
public class ProgrammableBillingProvider implements PaymentProvider {

    private final Map<String, SubscriptionSnapshot> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, PaymentSnapshot> payments = new ConcurrentHashMap<>();
    private final Map<String, CheckoutSnapshot> checkouts = new ConcurrentHashMap<>();

    public void putSubscription(SubscriptionSnapshot snapshot) {
        subscriptions.put(snapshot.providerSubscriptionId(), snapshot);
    }

    public void putPayment(PaymentSnapshot snapshot) {
        payments.put(snapshot.providerPaymentId(), snapshot);
    }

    public void putCheckout(CheckoutSnapshot snapshot) {
        checkouts.put(snapshot.providerSessionId(), snapshot);
    }

    public void reset() {
        subscriptions.clear();
        payments.clear();
        checkouts.clear();
    }

    @Override
    public Optional<SubscriptionSnapshot> getSubscription(String id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    @Override
    public Optional<PaymentSnapshot> getPayment(String id) {
        return Optional.ofNullable(payments.get(id));
    }

    @Override
    public Optional<CheckoutSnapshot> getCheckoutSession(String id) {
        return Optional.ofNullable(checkouts.get(id));
    }

    @Override
    public CustomerRef createCustomer(CreateCustomerRequest r) {
        return new CustomerRef("cus_fake");
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutSessionRequest r) {
        return new CheckoutSession("cs_fake", "https://fake/checkout");
    }

    @Override
    public PortalSession createPortalSession(PortalSessionRequest r) {
        return new PortalSession("https://fake/portal");
    }

    @Override
    public void cancelSubscription(CancelSubscriptionRequest r) {
    }

    @Override
    public RefundResult refund(RefundRequest r) {
        return new RefundResult("re_fake", "succeeded");
    }

    @Override
    public ProviderWebhookEvent verifyWebhook(byte[] payload, Map<String, String> headers) {
        throw new UnsupportedOperationException("verifyWebhook not used in these tests");
    }
}
