package io.bunnycal.hostpayments.provider;

import io.bunnycal.hostpayments.domain.PaymentProviderType;
import java.util.Map;
import java.util.UUID;

public interface HostPaymentProvider {
    PaymentProviderType type();

    ConnectedAccount createConnectedAccount(UUID userId, String email, String name);

    String createOnboardingLink(String providerAccountId);

    ConnectedAccount retrieveConnectedAccount(String providerAccountId);

    CreatedPayment createPayment(CreatePayment request);

    ProviderPayment retrievePayment(String providerAccountId, String providerPaymentId);

    void cancelPayment(String providerAccountId, String providerPaymentId);

    void refundPayment(String providerAccountId, String providerPaymentId, String idempotencyKey);

    VerifiedWebhook verifyWebhook(byte[] payload, String signature);

    record ConnectedAccount(String accountId, boolean chargesEnabled, boolean payoutsEnabled,
                            boolean detailsSubmitted, String restrictionReason) {
        public boolean ready() {
            return chargesEnabled && payoutsEnabled && detailsSubmitted;
        }
    }

    record CreatePayment(UUID paymentId, UUID reservationId, String reservationKind,
                         String providerAccountId, long amountMinor, String currency,
                         String description, String receiptEmail, String idempotencyKey) {
    }

    record CreatedPayment(String providerPaymentId, String clientSecret, ProviderPaymentStatus status) {
    }

    record ProviderPayment(String providerPaymentId, String clientSecret, String chargeId, ProviderPaymentStatus status,
                           long amountMinor, String currency, Map<String, String> metadata) {
    }

    enum ProviderPaymentStatus {
        REQUIRES_ACTION, PROCESSING, SUCCEEDED, FAILED, CANCELLED
    }

    record VerifiedWebhook(String eventId, String eventType, String providerAccountId,
                           String providerPaymentId, Long amountRefundedMinor,
                           Long chargeAmountMinor, String rawPayload) {
    }
}
