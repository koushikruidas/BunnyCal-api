package io.bunnycal.payments.provider;

import java.time.Instant;
import org.springframework.lang.Nullable;

/**
 * Neutral, on-demand <em>read</em> snapshots of current provider state, returned by
 * {@link BillingProviderReader}. Distinct from {@link ProviderWebhookEvent}: a webhook is a
 * point-in-time notification, whereas a snapshot is the provider's authoritative current view
 * fetched when we ask. Reconciliation applies snapshots, not webhook payloads, so a dropped or
 * out-of-order webhook cannot corrupt state.
 *
 * <p>Money is in minor units (cents/paise). None of these reference an SDK type.
 */
public final class ProviderSnapshots {

    private ProviderSnapshots() {
    }

    /**
     * Current state of a subscription at the provider.
     *
     * @param providerUpdatedAt provider's own last-modified time when exposed; used to reject
     *                          applying a stale read over a newer one. May be {@code null} if the
     *                          provider does not expose it (then observation time is the tiebreak).
     */
    public record SubscriptionSnapshot(
            String providerSubscriptionId,
            @Nullable String providerCustomerId,
            @Nullable String userId,
            ProviderWebhookEvent.SubscriptionStatusSignal status,
            boolean cancelAtPeriodEnd,
            @Nullable Instant currentPeriodStart,
            @Nullable Instant currentPeriodEnd,
            @Nullable Instant providerUpdatedAt) {
    }

    /** Current state of a checkout session at the provider. */
    public record CheckoutSnapshot(
            String providerSessionId,
            CheckoutStatus status,
            @Nullable String providerSubscriptionId,
            @Nullable String providerCustomerId,
            @Nullable String providerPaymentId,
            @Nullable String userId,
            long expectedAmountMinor,
            @Nullable String currency) {

        /** Provider-neutral checkout outcome. */
        public enum CheckoutStatus {
            OPEN, COMPLETED, EXPIRED, FAILED, UNKNOWN
        }
    }

    /** Current state of a single payment at the provider. */
    public record PaymentSnapshot(
            String providerPaymentId,
            PaymentStatus status,
            @Nullable String providerSubscriptionId,
            @Nullable String providerCustomerId,
            @Nullable String providerInvoiceId,
            @Nullable String officialInvoiceNumber,
            @Nullable String officialInvoiceUrl,
            long subtotalMinor,
            long discountMinor,
            long totalMinor,
            @Nullable String currency,
            @Nullable Instant periodStart,
            @Nullable Instant periodEnd) {

        /** Provider-neutral payment outcome. */
        public enum PaymentStatus {
            SUCCEEDED, FAILED, PENDING, UNKNOWN
        }
    }
}
