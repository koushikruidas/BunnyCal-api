package io.bunnycal.payments.provider;

import java.util.UUID;

/**
 * Neutral request/response value objects exchanged with a {@link PaymentProvider}.
 * Grouped here to keep the provider package compact; none reference any SDK type.
 */
public final class ProviderRequests {

    private ProviderRequests() {
    }

    public record CreateCustomerRequest(UUID userId, String email, String name) {
    }

    /** Provider-side customer identifier. */
    public record CustomerRef(String providerCustomerId) {
    }

    public record CheckoutSessionRequest(
            UUID userId,
            String providerCustomerId,
            String providerPriceId,
            Integer trialDays,
            String successUrl,
            String cancelUrl) {
    }

    public record CheckoutSession(String sessionId, String redirectUrl) {
    }

    public record PortalSessionRequest(String providerCustomerId, String returnUrl) {
    }

    public record PortalSession(String redirectUrl) {
    }

    public record CancelSubscriptionRequest(String providerSubscriptionId, boolean atPeriodEnd) {
    }

    public record RefundRequest(
            String providerPaymentIntentId,
            Long amountMinor,
            String reasonCode) {
    }

    public record RefundResult(String providerRefundId, String status) {
    }
}
