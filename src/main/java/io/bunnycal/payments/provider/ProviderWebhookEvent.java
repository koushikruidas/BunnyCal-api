package io.bunnycal.payments.provider;

import java.time.Instant;
import org.springframework.lang.Nullable;

/**
 * A verified, provider-agnostic webhook event.
 *
 * <p>The provider implementation ({@code StripeProvider} / {@code DodoProvider}) verifies
 * the signature and normalizes the raw event into this neutral shape: a {@link BillingEventType}
 * plus pre-extracted fields the domain handler needs. The handler therefore contains no
 * provider-specific event-type strings or JSON field names.
 *
 * @param providerEventId the provider's unique event id — the idempotency anchor
 *                        (persisted UNIQUE in {@code webhook_events})
 * @param rawType         the original provider event type string (audit/logging only)
 * @param type            the normalized {@link BillingEventType} the handler switches on
 * @param rawPayload      the original JSON body, retained for audit
 * @param data            pre-extracted neutral fields (may be empty for UNKNOWN events)
 */
public record ProviderWebhookEvent(
        String providerEventId,
        String rawType,
        BillingEventType type,
        String rawPayload,
        Data data) {

    /**
     * Neutral, pre-extracted fields. A given event populates only the subset relevant to its
     * {@link BillingEventType}; the rest are null/zero. Money is in minor units (cents/paise).
     */
    public record Data(
            @Nullable String providerSubscriptionId,
            @Nullable String providerCustomerId,
            @Nullable String userId,
            @Nullable SubscriptionStatusSignal status,
            boolean cancelAtPeriodEnd,
            @Nullable Instant currentPeriodStart,
            @Nullable Instant currentPeriodEnd,
            // Invoice (INVOICE_PAID / INVOICE_FAILED)
            @Nullable String providerInvoiceId,
            @Nullable String providerPaymentIntentId,
            long subtotalMinor,
            long discountMinor,
            long totalMinor,
            @Nullable String currency,
            @Nullable Instant invoicePeriodStart,
            @Nullable Instant invoicePeriodEnd,
            // Payment method mirror (display only; optional)
            @Nullable CardInfo card,
            @Nullable String providerPaymentMethodId,
            // Refund (REFUND_PROCESSED)
            @Nullable String refundProviderInvoiceId,
            @Nullable String providerRefundId,
            long amountRefundedMinor) {

        public static Data empty() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        /** Fluent builder — the {@link Data} record has many optional fields. */
        public static final class Builder {
            private String providerSubscriptionId;
            private String providerCustomerId;
            private String userId;
            private SubscriptionStatusSignal status;
            private boolean cancelAtPeriodEnd;
            private Instant currentPeriodStart;
            private Instant currentPeriodEnd;
            private String providerInvoiceId;
            private String providerPaymentIntentId;
            private long subtotalMinor;
            private long discountMinor;
            private long totalMinor;
            private String currency;
            private Instant invoicePeriodStart;
            private Instant invoicePeriodEnd;
            private CardInfo card;
            private String providerPaymentMethodId;
            private String refundProviderInvoiceId;
            private String providerRefundId;
            private long amountRefundedMinor;

            public Builder providerSubscriptionId(String v) { this.providerSubscriptionId = v; return this; }
            public Builder providerCustomerId(String v) { this.providerCustomerId = v; return this; }
            public Builder userId(String v) { this.userId = v; return this; }
            public Builder status(SubscriptionStatusSignal v) { this.status = v; return this; }
            public Builder cancelAtPeriodEnd(boolean v) { this.cancelAtPeriodEnd = v; return this; }
            public Builder currentPeriodStart(Instant v) { this.currentPeriodStart = v; return this; }
            public Builder currentPeriodEnd(Instant v) { this.currentPeriodEnd = v; return this; }
            public Builder providerInvoiceId(String v) { this.providerInvoiceId = v; return this; }
            public Builder providerPaymentIntentId(String v) { this.providerPaymentIntentId = v; return this; }
            public Builder subtotalMinor(long v) { this.subtotalMinor = v; return this; }
            public Builder discountMinor(long v) { this.discountMinor = v; return this; }
            public Builder totalMinor(long v) { this.totalMinor = v; return this; }
            public Builder currency(String v) { this.currency = v; return this; }
            public Builder invoicePeriodStart(Instant v) { this.invoicePeriodStart = v; return this; }
            public Builder invoicePeriodEnd(Instant v) { this.invoicePeriodEnd = v; return this; }
            public Builder card(CardInfo v) { this.card = v; return this; }
            public Builder providerPaymentMethodId(String v) { this.providerPaymentMethodId = v; return this; }
            public Builder refundProviderInvoiceId(String v) { this.refundProviderInvoiceId = v; return this; }
            public Builder providerRefundId(String v) { this.providerRefundId = v; return this; }
            public Builder amountRefundedMinor(long v) { this.amountRefundedMinor = v; return this; }

            public Data build() {
                return new Data(providerSubscriptionId, providerCustomerId, userId, status,
                        cancelAtPeriodEnd, currentPeriodStart, currentPeriodEnd,
                        providerInvoiceId, providerPaymentIntentId, subtotalMinor, discountMinor,
                        totalMinor, currency, invoicePeriodStart, invoicePeriodEnd,
                        card, providerPaymentMethodId, refundProviderInvoiceId,
                        providerRefundId, amountRefundedMinor);
            }
        }
    }

    /** Card display fields, mirrored from the provider when present. */
    public record CardInfo(
            @Nullable String brand,
            @Nullable String last4,
            @Nullable Integer expMonth,
            @Nullable Integer expYear) {
    }

    /**
     * Provider-neutral subscription status signal. Each provider maps its own status strings
     * onto these; the domain handler converts to its own {@code SubscriptionStatus}.
     */
    public enum SubscriptionStatusSignal {
        TRIAL, ACTIVE, PAST_DUE, CANCELLED, INCOMPLETE
    }
}
